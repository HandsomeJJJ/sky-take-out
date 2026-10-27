package com.sky.service;

import com.sky.context.BaseContext;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.MqMessage;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.lock.RedisLock;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.MqMessageMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.messaging.MessagingSwitch;
import com.sky.service.impl.OrderServiceImpl;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderSubmitVO;
import com.sky.websocket.WebSocketMessagePublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单主链路的核心规则：金额由服务端算、数据必须属于当前用户、状态推进必须带前置条件。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderDetailMapper orderDetailMapper;
    @Mock
    private AddressBookMapper addressBookMapper;
    @Mock
    private ShoppingCartMapper shoppingCartMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private WeChatPayUtil weChatPayUtil;
    @Mock
    private WebSocketMessagePublisher webSocketMessagePublisher;
    @Mock
    private RedisLock redisLock;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private MqMessageMapper mqMessageMapper;
    @Mock
    private MessagingSwitch messagingSwitch;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
        // submitOrder 会注册事务同步回调，脱离真实事务时需要手工开启同步上下文
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        BaseContext.removeCurrentId();
    }

    private AddressBook addressBook(Long userId) {
        AddressBook book = new AddressBook();
        book.setId(9L);
        book.setUserId(userId);
        book.setConsignee("张三");
        book.setPhone("13800000000");
        book.setDetail("海淀区中关村大街1号");
        return book;
    }

    private ShoppingCart cartItem(String name, String unitPrice, int number) {
        return ShoppingCart.builder()
                .id(1L)
                .name(name)
                .amount(new BigDecimal(unitPrice))
                .number(number)
                .build();
    }

    private OrdersSubmitDTO submitDTO() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setAddressBookId(9L);
        dto.setPayMethod(1);
        dto.setPackAmount(3);
        dto.setTablewareNumber(2);
        dto.setTablewareStatus(1);
        dto.setDeliveryStatus(1);
        return dto;
    }

    @Test
    @DisplayName("订单金额由服务端按购物车明细计算：2×10.00 + 1×20.50 + 打包费3 = 43.50")
    void submitOrderComputesAmountOnServer() {
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(messagingSwitch.isEnabledFor(anyString())).thenReturn(true);
        when(addressBookMapper.getByIdAndUserId(9L, USER_ID)).thenReturn(addressBook(USER_ID));
        when(shoppingCartMapper.list(any(ShoppingCart.class)))
                .thenReturn(List.of(cartItem("宫保鸡丁", "10.00", 2), cartItem("套餐A", "20.50", 1)));

        OrderSubmitVO vo = orderService.submitOrder(submitDTO());

        ArgumentCaptor<Orders> captor = ArgumentCaptor.forClass(Orders.class);
        verify(orderMapper).insert(captor.capture());
        Orders saved = captor.getValue();

        assertThat(saved.getAmount()).isEqualByComparingTo("43.50");
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getAddressBookId()).isEqualTo(9L);
        assertThat(saved.getStatus()).isEqualTo(Orders.PENDING_PAYMENT);
        assertThat(saved.getPayStatus()).isEqualTo(Orders.UN_PAID);
        assertThat(saved.getNumber()).isNotBlank();
        assertThat(vo.getOrderAmount()).isEqualByComparingTo("43.50");

        // 超时取消 + 统计两条本地消息，保证 MQ 宕机后还能补偿重发
        verify(mqMessageMapper, times(2)).insert(any(MqMessage.class));
    }

    @Test
    @DisplayName("地址簿不属于当前用户时拒绝下单")
    void submitOrderRejectsForeignAddressBook() {
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(addressBookMapper.getByIdAndUserId(9L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.submitOrder(submitDTO()))
                .isInstanceOf(AddressBookBusinessException.class);

        verify(orderMapper, never()).insert(any(Orders.class));
    }

    @Test
    @DisplayName("支付回调：金额与订单应付不一致时不入账")
    void paySuccessRejectsAmountMismatch() {
        Orders orders = new Orders();
        orders.setId(5L);
        orders.setNumber("202610280001");
        orders.setAmount(new BigDecimal("50.00"));
        orders.setPayStatus(Orders.UN_PAID);
        when(orderMapper.getByNumber("202610280001")).thenReturn(orders);

        assertThatThrownBy(() -> orderService.paySuccess("202610280001", "SUCCESS", 1))
                .isInstanceOf(OrderBusinessException.class);

        verify(orderMapper, never()).markPaidByNumber(anyString(), any());
        verify(webSocketMessagePublisher, never()).broadcast(anyString());
    }

    @Test
    @DisplayName("支付回调：重复推送时条件更新影响 0 行，不再二次推送来单提醒")
    void paySuccessIsIdempotentOnDuplicateNotify() {
        Orders orders = new Orders();
        orders.setId(5L);
        orders.setNumber("202610280001");
        orders.setAmount(new BigDecimal("50.00"));
        orders.setPayStatus(Orders.PAID);
        when(orderMapper.getByNumber("202610280001")).thenReturn(orders);
        when(orderMapper.markPaidByNumber(eq("202610280001"), any())).thenReturn(0);

        orderService.paySuccess("202610280001", "SUCCESS", 5000);

        verify(webSocketMessagePublisher, never()).broadcast(anyString());
    }

    @Test
    @DisplayName("支付回调：状态非 SUCCESS 不动订单")
    void paySuccessIgnoresNonSuccessTradeState() {
        orderService.paySuccess("202610280001", "PAYERROR", 5000);

        verify(orderMapper, never()).getByNumber(anyString());
        verify(orderMapper, never()).markPaidByNumber(anyString(), any());
    }

    @Test
    @DisplayName("支付回调：金额一致时先条件更新成功才推送来单提醒")
    void paySuccessMarksPaidAndNotifies() {
        Orders orders = new Orders();
        orders.setId(5L);
        orders.setNumber("202610280001");
        orders.setAmount(new BigDecimal("50.00"));
        orders.setPayStatus(Orders.UN_PAID);
        when(orderMapper.getByNumber("202610280001")).thenReturn(orders);
        when(orderMapper.markPaidByNumber(eq("202610280001"), any())).thenReturn(1);

        orderService.paySuccess("202610280001", "SUCCESS", 5000);

        verify(orderMapper).markPaidByNumber(eq("202610280001"), any());
        verify(webSocketMessagePublisher).broadcast(anyString());
    }

    @Test
    @DisplayName("用户不能取消别人的订单")
    void userCancelRejectsForeignOrder() {
        when(orderMapper.getByIdAndUserId(5L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.userCancelById(5L))
                .isInstanceOf(OrderBusinessException.class);

        verify(orderMapper, never()).cancelIfStatusMatches(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("管理端不能取消已完成/已取消的订单，避免对同一笔订单反复退款")
    void adminCancelRejectsFinishedOrder() throws Exception {
        Orders done = new Orders();
        done.setId(7L);
        done.setStatus(Orders.COMPLETED);
        done.setPayStatus(Orders.PAID);
        done.setAmount(new BigDecimal("50.00"));
        when(orderMapper.getById(7L)).thenReturn(done);

        com.sky.dto.OrdersCancelDTO dto = new com.sky.dto.OrdersCancelDTO();
        dto.setId(7L);
        dto.setCancelReason("商家取消");

        assertThatThrownBy(() -> orderService.cancel(dto)).isInstanceOf(OrderBusinessException.class);
        verify(weChatPayUtil, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("管理端取消：先把状态落库，之后才发起退款")
    void adminCancelPersistsBeforeRefund() throws Exception {
        Orders payable = new Orders();
        payable.setId(8L);
        payable.setNumber("202610280002");
        payable.setStatus(Orders.TO_BE_CONFIRMED);
        payable.setPayStatus(Orders.PAID);
        payable.setAmount(new BigDecimal("50.00"));
        when(orderMapper.getById(8L)).thenReturn(payable);
        when(orderMapper.cancelIfStatusMatches(eq(8L), eq(Orders.TO_BE_CONFIRMED), any(), any(), any(),
                eq(Orders.REFUND))).thenReturn(1);
        when(weChatPayUtil.refund(anyString(), anyString(), any(), any())).thenReturn("{}");

        com.sky.dto.OrdersCancelDTO dto = new com.sky.dto.OrdersCancelDTO();
        dto.setId(8L);
        dto.setCancelReason("餐品售罄");

        orderService.cancel(dto);

        verify(orderMapper).cancelIfStatusMatches(eq(8L), eq(Orders.TO_BE_CONFIRMED), any(), any(), any(),
                eq(Orders.REFUND));
        verify(weChatPayUtil).refund(eq("202610280002"), eq("202610280002"), any(), any());
    }

    @Test
    @DisplayName("详情/催单都按当前用户过滤，不能靠遍历 id 读别人的订单")
    void userScopedReads() {
        when(orderMapper.getByIdAndUserId(5L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.userOrderDetails(5L)).isInstanceOf(OrderBusinessException.class);
        assertThatThrownBy(() -> orderService.reminder(5L)).isInstanceOf(OrderBusinessException.class);
        assertThatThrownBy(() -> orderService.repetition(5L)).isInstanceOf(OrderBusinessException.class);

        verify(orderMapper, never()).getById(anyLong());
        verify(webSocketMessagePublisher, never()).broadcast(anyString());
    }

}
