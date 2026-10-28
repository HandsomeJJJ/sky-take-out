package com.sky.service;

import com.sky.context.BaseContext;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.Dish;
import com.sky.entity.MqMessage;
import com.sky.entity.OrderDetail;
import com.sky.entity.OrderNotification;
import com.sky.entity.Orders;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.lock.RedisLock;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.MqMessageMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.messaging.MessagingSwitch;
import com.sky.properties.OrderProperties;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单主链路的核心规则：金额与打包费由服务端算、商品必须仍在售、数据必须属于当前用户、
 * 状态推进必须带前置条件、取消与退款必须闭环。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final Long USER_ID = 100L;
    private static final Long DISH_ID = 11L;
    private static final Long SETMEAL_ID = 21L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderDetailMapper orderDetailMapper;
    @Mock
    private AddressBookMapper addressBookMapper;
    @Mock
    private ShoppingCartMapper shoppingCartMapper;
    @Mock
    private DishMapper dishMapper;
    @Mock
    private SetmealMapper setmealMapper;
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
    @Mock
    private ShopStatusService shopStatusService;
    @Mock
    private OrderRefundService orderRefundService;
    @Mock
    private OrderNotificationService notificationService;

    /** 打包费规则用真实对象：它是被测逻辑的输入， mocking 它等于把要验证的算价规则也 mock 掉了 */
    @Spy
    private OrderProperties orderProperties = orderProperties();

    @InjectMocks
    private OrderServiceImpl orderService;

    private static OrderProperties orderProperties() {
        OrderProperties properties = new OrderProperties();
        properties.setPackFeePerItem(new BigDecimal("1.00"));
        properties.setPackFeeMax(new BigDecimal("20.00"));
        properties.setMaxNumberPerItem(99);
        return properties;
    }

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
        // submitOrder 会注册事务同步回调，脱离真实事务时需要手工开启同步上下文
        TransactionSynchronizationManager.initSynchronization();
        lenient().when(shopStatusService.isOpen()).thenReturn(true);
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

    private ShoppingCart dishCart(String snapshotPrice, int number) {
        return ShoppingCart.builder()
                .id(1L)
                .name("宫保鸡丁")
                .dishId(DISH_ID)
                .amount(new BigDecimal(snapshotPrice))
                .number(number)
                .build();
    }

    private ShoppingCart setmealCart(String snapshotPrice, int number) {
        return ShoppingCart.builder()
                .id(2L)
                .name("双人套餐")
                .setmealId(SETMEAL_ID)
                .amount(new BigDecimal(snapshotPrice))
                .number(number)
                .build();
    }

    private Dish dish(String price, Integer status) {
        Dish dish = new Dish();
        dish.setId(DISH_ID);
        dish.setName("宫保鸡丁");
        dish.setImage("dish.png");
        dish.setPrice(new BigDecimal(price));
        dish.setStatus(status);
        return dish;
    }

    private Setmeal setmeal(String price, Integer status) {
        Setmeal setmeal = new Setmeal();
        setmeal.setId(SETMEAL_ID);
        setmeal.setName("双人套餐");
        setmeal.setImage("setmeal.png");
        setmeal.setPrice(new BigDecimal(price));
        setmeal.setStatus(status);
        return setmeal;
    }

    private OrdersSubmitDTO submitDTO() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setAddressBookId(9L);
        dto.setPayMethod(1);
        dto.setTablewareNumber(2);
        dto.setTablewareStatus(1);
        dto.setDeliveryStatus(1);
        return dto;
    }

    private Orders order(String number, Integer status, Integer payStatus, String amount) {
        Orders orders = new Orders();
        orders.setId(5L);
        orders.setUserId(USER_ID);
        orders.setNumber(number);
        orders.setStatus(status);
        orders.setPayStatus(payStatus);
        orders.setAmount(new BigDecimal(amount));
        return orders;
    }

    @Test
    @DisplayName("订单金额与打包费都由服务端计算：2×10.00 + 1×20.50 + 打包费 3×1.00 = 43.50")
    void submitOrderComputesAmountAndPackFeeOnServer() {
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(messagingSwitch.isEnabledFor(anyString())).thenReturn(true);
        when(addressBookMapper.getByIdAndUserId(9L, USER_ID)).thenReturn(addressBook(USER_ID));
        when(shoppingCartMapper.list(any(ShoppingCart.class)))
                .thenReturn(List.of(dishCart("10.00", 2), setmealCart("20.50", 1)));
        when(dishMapper.getByIds(List.of(DISH_ID))).thenReturn(List.of(dish("10.00", 1)));
        when(setmealMapper.getByIds(List.of(SETMEAL_ID))).thenReturn(List.of(setmeal("20.50", 1)));

        OrderSubmitVO vo = orderService.submitOrder(submitDTO());

        ArgumentCaptor<Orders> captor = ArgumentCaptor.forClass(Orders.class);
        verify(orderMapper).insert(captor.capture());
        Orders saved = captor.getValue();

        assertThat(saved.getPackAmount()).isEqualByComparingTo("3.00");
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
    @DisplayName("下单按现价算：购物车里的历史快照价不作数")
    void submitOrderUsesCurrentPriceInsteadOfCartSnapshot() {
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(messagingSwitch.isEnabledFor(anyString())).thenReturn(true);
        when(addressBookMapper.getByIdAndUserId(9L, USER_ID)).thenReturn(addressBook(USER_ID));
        // 加购时 10 元，现在商家改价 12 元
        when(shoppingCartMapper.list(any(ShoppingCart.class))).thenReturn(List.of(dishCart("10.00", 1)));
        when(dishMapper.getByIds(List.of(DISH_ID))).thenReturn(List.of(dish("12.00", 1)));

        orderService.submitOrder(submitDTO());

        ArgumentCaptor<Orders> orderCaptor = ArgumentCaptor.forClass(Orders.class);
        verify(orderMapper).insert(orderCaptor.capture());
        // 12.00 + 打包费 1.00
        assertThat(orderCaptor.getValue().getAmount()).isEqualByComparingTo("13.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderDetail>> detailCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderDetailMapper).insertBatch(detailCaptor.capture());
        assertThat(detailCaptor.getValue()).hasSize(1);
        assertThat(detailCaptor.getValue().get(0).getAmount()).isEqualByComparingTo("12.00");
        assertThat(detailCaptor.getValue().get(0).getName()).isEqualTo("宫保鸡丁");
        assertThat(detailCaptor.getValue().get(0).getOrderId()).isEqualTo(orderCaptor.getValue().getId());
    }

    @Test
    @DisplayName("打包费封顶：30 份 × 1.00 也只收 20.00")
    void submitOrderCapsPackFee() {
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(messagingSwitch.isEnabledFor(anyString())).thenReturn(true);
        when(addressBookMapper.getByIdAndUserId(9L, USER_ID)).thenReturn(addressBook(USER_ID));
        when(shoppingCartMapper.list(any(ShoppingCart.class))).thenReturn(List.of(dishCart("10.00", 30)));
        when(dishMapper.getByIds(List.of(DISH_ID))).thenReturn(List.of(dish("10.00", 1)));

        orderService.submitOrder(submitDTO());

        ArgumentCaptor<Orders> captor = ArgumentCaptor.forClass(Orders.class);
        verify(orderMapper).insert(captor.capture());
        assertThat(captor.getValue().getPackAmount()).isEqualByComparingTo("20.00");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("320.00");
    }

    @Test
    @DisplayName("加购后被下架的菜品不能成单：整单拒绝而不是静默少收")
    void submitOrderRejectsOffSaleItem() {
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(addressBookMapper.getByIdAndUserId(9L, USER_ID)).thenReturn(addressBook(USER_ID));
        when(shoppingCartMapper.list(any(ShoppingCart.class)))
                .thenReturn(List.of(dishCart("10.00", 1), setmealCart("20.50", 1)));
        when(dishMapper.getByIds(List.of(DISH_ID))).thenReturn(List.of(dish("10.00", 1)));
        // 套餐已停售
        when(setmealMapper.getByIds(List.of(SETMEAL_ID))).thenReturn(List.of(setmeal("20.50", 0)));

        assertThatThrownBy(() -> orderService.submitOrder(submitDTO()))
                .isInstanceOf(OrderBusinessException.class)
                .hasMessageContaining("双人套餐");

        verify(orderMapper, never()).insert(any(Orders.class));
    }

    @Test
    @DisplayName("已删除的菜品不在商品表里，同样按下架处理")
    void submitOrderRejectsDeletedDish() {
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(addressBookMapper.getByIdAndUserId(9L, USER_ID)).thenReturn(addressBook(USER_ID));
        when(shoppingCartMapper.list(any(ShoppingCart.class))).thenReturn(List.of(dishCart("10.00", 1)));
        when(dishMapper.getByIds(List.of(DISH_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.submitOrder(submitDTO()))
                .isInstanceOf(OrderBusinessException.class);

        verify(orderMapper, never()).insert(any(Orders.class));
    }

    @Test
    @DisplayName("打烊时不允许下单")
    void submitOrderRejectsWhenShopClosed() {
        when(shopStatusService.isOpen()).thenReturn(false);

        assertThatThrownBy(() -> orderService.submitOrder(submitDTO()))
                .isInstanceOf(OrderBusinessException.class)
                .hasMessageContaining("打烊");

        // 打烊判断在加锁之前，不给无效请求占锁
        verify(redisLock, never()).tryLock(anyString(), any(Duration.class));
        verify(orderMapper, never()).insert(any(Orders.class));
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
    @DisplayName("已取消的订单不能发起支付：超时取消的单 pay_status 仍是 0")
    void paymentRejectsCancelledOrder() throws Exception {
        when(userMapper.getById(USER_ID)).thenReturn(new com.sky.entity.User());
        when(orderMapper.getByNumberAndUserId("202610280001", USER_ID))
                .thenReturn(order("202610280001", Orders.CANCELLED, Orders.UN_PAID, "50.00"));

        com.sky.dto.OrdersPaymentDTO dto = new com.sky.dto.OrdersPaymentDTO();
        dto.setOrderNumber("202610280001");
        dto.setPayMethod(1);

        assertThatThrownBy(() -> orderService.payment(dto)).isInstanceOf(OrderBusinessException.class);
        verify(weChatPayUtil, never()).pay(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("支付回调：金额与订单应付不一致时不入账")
    void paySuccessRejectsAmountMismatch() {
        when(orderMapper.getByNumber("202610280001"))
                .thenReturn(order("202610280001", Orders.PENDING_PAYMENT, Orders.UN_PAID, "50.00"));

        assertThatThrownBy(() -> orderService.paySuccess("202610280001", "SUCCESS", 1))
                .isInstanceOf(OrderBusinessException.class);

        verify(orderMapper, never()).markPaidByNumber(anyString(), any());
        verify(webSocketMessagePublisher, never()).broadcast(anyString());
    }

    @Test
    @DisplayName("支付回调：重复推送时条件更新影响 0 行，不再二次推送来单提醒")
    void paySuccessIsIdempotentOnDuplicateNotify() {
        when(orderMapper.getByNumber("202610280001"))
                .thenReturn(order("202610280001", Orders.TO_BE_CONFIRMED, Orders.PAID, "50.00"));
        when(orderMapper.markPaidByNumber(eq("202610280001"), any())).thenReturn(0);

        orderService.paySuccess("202610280001", "SUCCESS", 5000);

        verify(webSocketMessagePublisher, never()).broadcast(anyString());
        // 已经入过账的重复回调不该再触发退款
        verify(orderRefundService, never()).applyRefund(any(), anyString());
    }

    @Test
    @DisplayName("支付回调：状态非 SUCCESS 不动订单")
    void paySuccessIgnoresNonSuccessTradeState() {
        orderService.paySuccess("202610280001", "PAYERROR", 5000);

        verify(orderMapper, never()).getByNumber(anyString());
        verify(orderMapper, never()).markPaidByNumber(anyString(), any());
    }

    @Test
    @DisplayName("支付回调：金额一致时先条件更新成功才推送来单提醒与用户通知")
    void paySuccessMarksPaidAndNotifies() {
        when(orderMapper.getByNumber("202610280001"))
                .thenReturn(order("202610280001", Orders.PENDING_PAYMENT, Orders.UN_PAID, "50.00"));
        when(orderMapper.markPaidByNumber(eq("202610280001"), any())).thenReturn(1);

        orderService.paySuccess("202610280001", "SUCCESS", 5000);

        verify(orderMapper).markPaidByNumber(eq("202610280001"), any());
        verify(webSocketMessagePublisher).broadcast(anyString());
        verify(notificationService).notifyOrder(any(), eq(OrderNotification.TYPE_PAID), anyString(), anyString());
    }

    @Test
    @DisplayName("迟到支付：订单已取消却收到成功回调，登记支付并自动发起全额退款")
    void paySuccessOnCancelledOrderTriggersRefund() {
        when(orderMapper.getByNumber("202610280001"))
                .thenReturn(order("202610280001", Orders.CANCELLED, Orders.UN_PAID, "50.00"));
        when(orderMapper.markPaidByNumber(eq("202610280001"), any())).thenReturn(0);
        when(orderMapper.markLatePayment(eq("202610280001"), any())).thenReturn(1);

        orderService.paySuccess("202610280001", "SUCCESS", 5000);

        verify(orderMapper).markLatePayment(eq("202610280001"), any());
        verify(orderRefundService).applyRefund(any(), anyString());
        verify(webSocketMessagePublisher, never()).broadcast(anyString());
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
        when(orderMapper.getById(7L)).thenReturn(order("202610280001", Orders.COMPLETED, Orders.PAID, "50.00"));

        OrdersCancelDTO dto = new OrdersCancelDTO();
        dto.setId(7L);
        dto.setCancelReason("商家取消");

        assertThatThrownBy(() -> orderService.cancel(dto)).isInstanceOf(OrderBusinessException.class);
        verify(orderRefundService, never()).applyRefund(any(), anyString());
    }

    @Test
    @DisplayName("管理端取消：先落状态，再通知用户，最后发起退款")
    void adminCancelPersistsBeforeRefund() throws Exception {
        Orders payable = order("202610280002", Orders.TO_BE_CONFIRMED, Orders.PAID, "50.00");
        payable.setId(8L);
        when(orderMapper.getById(8L)).thenReturn(payable);
        when(orderMapper.cancelIfStatusMatches(eq(8L), eq(Orders.TO_BE_CONFIRMED), any(), any(), any(),
                eq(Orders.REFUND))).thenReturn(1);

        OrdersCancelDTO dto = new OrdersCancelDTO();
        dto.setId(8L);
        dto.setCancelReason("餐品售罄");

        orderService.cancel(dto);

        verify(orderMapper).cancelIfStatusMatches(eq(8L), eq(Orders.TO_BE_CONFIRMED), any(), any(), any(),
                eq(Orders.REFUND));
        verify(notificationService).notifyOrder(any(), eq(OrderNotification.TYPE_CANCELLED), anyString(), anyString());
        verify(orderRefundService).applyRefund(eq(payable), anyString());
    }

    @Test
    @DisplayName("拒单后必须通知用户，而不是只在管理端列表里改个状态")
    void rejectionNotifiesUser() throws Exception {
        Orders paid = order("202610280003", Orders.TO_BE_CONFIRMED, Orders.PAID, "50.00");
        paid.setId(9L);
        when(orderMapper.getById(9L)).thenReturn(paid);
        when(orderMapper.cancelIfStatusMatches(eq(9L), eq(Orders.TO_BE_CONFIRMED), any(), any(), any(),
                eq(Orders.REFUND))).thenReturn(1);

        com.sky.dto.OrdersRejectionDTO dto = new com.sky.dto.OrdersRejectionDTO();
        dto.setId(9L);
        dto.setRejectionReason("食材不足");

        orderService.rejection(dto);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyOrder(any(), eq(OrderNotification.TYPE_REJECTED), anyString(),
                content.capture());
        assertThat(content.getValue()).contains("食材不足");
        verify(orderRefundService).applyRefund(eq(paid), anyString());
    }

    @Test
    @DisplayName("接单成功后给用户留一条通知")
    void confirmNotifiesUser() {
        when(orderMapper.updateStatus(12L, Orders.TO_BE_CONFIRMED, Orders.CONFIRMED)).thenReturn(1);
        Orders confirmed = order("202610280004", Orders.CONFIRMED, Orders.PAID, "50.00");
        confirmed.setId(12L);
        when(orderMapper.getById(12L)).thenReturn(confirmed);

        OrdersConfirmDTO dto = new OrdersConfirmDTO();
        dto.setId(12L);
        orderService.confirm(dto);

        verify(notificationService).notifyOrder(eq(confirmed), eq(OrderNotification.TYPE_CONFIRMED),
                anyString(), anyString());
    }

    @Test
    @DisplayName("状态前置条件不满足时接单失败，也不产生通知")
    void confirmRejectsWhenStatusChanged() {
        when(orderMapper.updateStatus(13L, Orders.TO_BE_CONFIRMED, Orders.CONFIRMED)).thenReturn(0);

        OrdersConfirmDTO dto = new OrdersConfirmDTO();
        dto.setId(13L);

        assertThatThrownBy(() -> orderService.confirm(dto)).isInstanceOf(OrderBusinessException.class);
        verify(notificationService, never()).notifyOrder(any(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("再来一单：下架的跳过，在售的按现价合并进已有购物车行")
    void repetitionMergesIntoExistingCartWithCurrentPrice() {
        Orders own = order("202610280005", Orders.COMPLETED, Orders.PAID, "50.00");
        own.setId(15L);
        when(orderMapper.getByIdAndUserId(15L, USER_ID)).thenReturn(own);

        OrderDetail detail = new OrderDetail();
        detail.setOrderId(15L);
        detail.setDishId(DISH_ID);
        detail.setName("宫保鸡丁");
        detail.setNumber(2);
        detail.setAmount(new BigDecimal("9.99"));// 成交时的历史价
        when(orderDetailMapper.getByOrderId(15L)).thenReturn(List.of(detail));

        when(dishMapper.getByIds(List.of(DISH_ID))).thenReturn(List.of(dish("12.00", 1)));
        when(shoppingCartMapper.list(any(ShoppingCart.class)))
                .thenReturn(List.of(dishCart("12.00", 1)));
        when(shoppingCartMapper.increaseNumberByDelta(1L, 2, 99)).thenReturn(1);

        orderService.repetition(15L);

        // 已有同一 SKU：只做累加，不再插入新行（否则撞购物车唯一索引）
        verify(shoppingCartMapper).increaseNumberByDelta(1L, 2, 99);
        verify(shoppingCartMapper, never()).insertBatch(anyList());
    }

    @Test
    @DisplayName("再来一单：全部商品下架时明确报错，不给出一个空购物车")
    void repetitionRejectsWhenEverythingOffSale() {
        Orders own = order("202610280006", Orders.COMPLETED, Orders.PAID, "50.00");
        own.setId(16L);
        when(orderMapper.getByIdAndUserId(16L, USER_ID)).thenReturn(own);

        OrderDetail detail = new OrderDetail();
        detail.setOrderId(16L);
        detail.setDishId(DISH_ID);
        detail.setName("宫保鸡丁");
        detail.setNumber(1);
        when(orderDetailMapper.getByOrderId(16L)).thenReturn(List.of(detail));
        when(dishMapper.getByIds(List.of(DISH_ID))).thenReturn(List.of(dish("12.00", 0)));

        assertThatThrownBy(() -> orderService.repetition(16L)).isInstanceOf(OrderBusinessException.class);
        verify(shoppingCartMapper, never()).insertBatch(anyList());
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
