package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.event.OrderCreatedEvent;
import com.sky.lock.RedisLock;
import com.sky.mapper.*;
import com.sky.messaging.MessagingSwitch;
import com.sky.messaging.MqMessageSender;
import com.sky.properties.OrderProperties;
import com.sky.result.PageResult;
import com.sky.service.OrderNotificationService;
import com.sky.service.OrderRefundService;
import com.sky.service.OrderService;
import com.sky.service.ShopStatusService;
import com.sky.utils.JsonUtils;
import com.sky.utils.SnowflakeIdGenerator;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    /** 分页上限，防止 pageSize 传一个亿把整表捞进内存 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 微信支付回调里表示交易成功的状态 */
    private static final String WECHAT_TRADE_SUCCESS = "SUCCESS";

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private WebSocketMessagePublisher webSocketMessagePublisher;
    @Autowired
    private RedisLock redisLock;
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    @Autowired
    private MqMessageMapper mqMessageMapper;
    @Autowired
    private MessagingSwitch messagingSwitch;
    @Autowired
    private ShopStatusService shopStatusService;
    @Autowired
    private OrderRefundService orderRefundService;
    @Autowired
    private OrderNotificationService notificationService;
    @Autowired
    private OrderProperties orderProperties;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {

        Long userId = BaseContext.getCurrentId();

        // 打烊拦截：营业状态原来只有 ShopController 读写，下单链路根本不参考，
        // 设置成"打烊"后照样能收单，商家实际无法出餐。
        if (!shopStatusService.isOpen()) {
            throw new OrderBusinessException(MessageConstant.SHOP_CLOSED);
        }

        String lockKey = "order:submit:lock:" + userId;
        // Redisson 锁：watchdog 自动续期，业务执行多久锁就持有多久，不会提前失效
        if (!redisLock.tryLock(lockKey, Duration.ofSeconds(1))) {
            throw new OrderBusinessException("订单正在提交，请勿重复操作");
        }
        // 锁持有到事务完成，避免在数据库提交前被下一次重复请求获取。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                redisLock.unlock(lockKey);
            }
        });

        //1. 处理各种业务异常（地址簿为空、购物车数据为空）
        // 地址簿必须属于当前用户，否则可以用别人的地址下单，把别人的手机号+住址写进订单
        AddressBook addressBook = addressBookMapper.getByIdAndUserId(ordersSubmitDTO.getAddressBookId(), userId);
        if (addressBook == null) {
            //抛出业务异常
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        //查询当前用户的购物车数据
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

        if (CollectionUtils.isEmpty(shoppingCartList)) {
            //抛出业务异常
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 下单前复核：加购之后菜品可能已被停售或删除，购物车行里存的还是历史快照价，
        // 必须按"当前是否在售 + 当前价格"重算，否则下架菜能成单、改价的差额由平台承担。
        List<ResolvedItem> items = resolveCartItems(shoppingCartList);

        //2. 向订单表插入1条数据
        Orders orders = new Orders();
        orders.setNumber(String.valueOf(SnowflakeIdGenerator.getInstance().nextId()));
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setUserId(userId);
        orders.setAddressBookId(addressBook.getId());
        orders.setPayMethod(ordersSubmitDTO.getPayMethod());
        orders.setRemark(ordersSubmitDTO.getRemark());
        orders.setEstimatedDeliveryTime(ordersSubmitDTO.getEstimatedDeliveryTime());
        orders.setDeliveryStatus(ordersSubmitDTO.getDeliveryStatus());
        orders.setTablewareNumber(ordersSubmitDTO.getTablewareNumber());
        orders.setTablewareStatus(ordersSubmitDTO.getTablewareStatus());
        // 打包费同样由服务端按配置单价 × 份数计算，客户端没有填金额的入口
        BigDecimal packAmount = calculatePackAmount(items);
        orders.setPackAmount(packAmount);
        // 金额只由服务端计算，客户端没有可填写金额的入口
        orders.setAmount(calculateOrderAmount(items, packAmount));
        orders.setAddress(addressBook.getDetail());
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());

        orderMapper.insert(orders);

        List<OrderDetail> orderDetailList = new ArrayList<>();
        //3. 向订单明细表插入n条数据
        for (ResolvedItem item : items) {
            OrderDetail orderDetail = new OrderDetail();
            // 明细的 id 不复用购物车行 id；名称/单价一律取复核后的当前值
            orderDetail.setOrderId(orders.getId());//设置当前订单明细关联的订单id
            orderDetail.setName(item.name());
            orderDetail.setImage(item.image());
            orderDetail.setDishId(item.dishId());
            orderDetail.setSetmealId(item.setmealId());
            orderDetail.setDishFlavor(item.dishFlavor());
            orderDetail.setNumber(item.number());
            orderDetail.setAmount(item.price());
            orderDetailList.add(orderDetail);
        }

        orderDetailMapper.insertBatch(orderDetailList);

        //4. 清空当前用户的购物车数据
        shoppingCartMapper.deleteByUserId(userId);

        //5. 封装VO返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .build();

        // 事件由事务提交后的监听器触发立即发送；消息本体已在事务内写入 mq_message 表，
        // 即使这里投递失败，也会被补偿任务重发，保证"业务成功则消息必达"。
        OrderCreatedEvent event = new OrderCreatedEvent(orders.getId(), orders.getNumber(),
                userId, orders.getAmount(), orders.getOrderTime());
        String payload = JsonUtils.toJson(event);
        enqueueMessage(MqMessageSender.BIZ_ORDER_TIMEOUT, orders.getId(), payload);
        enqueueMessage(MqMessageSender.BIZ_ORDER_STATS, orders.getId(), payload);
        applicationEventPublisher.publishEvent(event);

        return orderSubmitVO;
    }

    /**
     * 登记一条本地消息；对应 MQ 未启用时不登记，
     * 超时会由 OrderTimeoutFallbackTask 兜底，不会因此丢单。
     */
    private void enqueueMessage(String bizType, Long orderId, String payload) {
        if (!messagingSwitch.isEnabledFor(bizType)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        mqMessageMapper.insert(MqMessage.builder()
                .bizType(bizType)
                .bizId(String.valueOf(orderId))
                .payload(payload)
                .status(MqMessage.PENDING)
                .retryCount(0)
                .nextRetryTime(now)
                .createTime(now)
                .updateTime(now)
                .build());
    }

    /**
     * 下单前的一次复核：把购物车行换成"当前真实在售、当前价格"的明细。
     * 只要有一行已经下架/被删除就整单拒绝，而不是静默少收一件商品。
     */
    private List<ResolvedItem> resolveCartItems(List<ShoppingCart> cartList) {
        return resolveItems(cartList, true);
    }

    /**
     * 按当前商品表复核每一行的在售状态与价格。
     *
     * @param strict true 时任何一行不可售都抛业务异常（下单）；
     *               false 时跳过不可售的行（"再来一单"不该因为一个菜下架就整个按钮点不动）
     */
    private List<ResolvedItem> resolveItems(List<ShoppingCart> cartList, boolean strict) {
        List<Long> dishIds = cartList.stream().map(ShoppingCart::getDishId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        List<Long> setmealIds = cartList.stream().map(ShoppingCart::getSetmealId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());

        Map<Long, Dish> dishMap = dishIds.isEmpty() ? Collections.emptyMap()
                : dishMapper.getByIds(dishIds).stream().collect(Collectors.toMap(Dish::getId, Function.identity()));
        Map<Long, Setmeal> setmealMap = setmealIds.isEmpty() ? Collections.emptyMap()
                : setmealMapper.getByIds(setmealIds).stream()
                .collect(Collectors.toMap(Setmeal::getId, Function.identity()));

        List<String> unavailable = new ArrayList<>();
        List<ResolvedItem> items = new ArrayList<>();
        for (ShoppingCart cart : cartList) {
            if (cart.getDishId() != null) {
                Dish dish = dishMap.get(cart.getDishId());
                if (dish == null || !StatusConstant.ENABLE.equals(dish.getStatus()) || dish.getPrice() == null) {
                    unavailable.add(cart.getName() == null ? "菜品" + cart.getDishId() : cart.getName());
                    continue;
                }
                items.add(new ResolvedItem(dish.getId(), null, dish.getName(), dish.getImage(),
                        cart.getDishFlavor(), cart.getNumber(), dish.getPrice()));
            } else if (cart.getSetmealId() != null) {
                Setmeal setmeal = setmealMap.get(cart.getSetmealId());
                if (setmeal == null || !StatusConstant.ENABLE.equals(setmeal.getStatus()) || setmeal.getPrice() == null) {
                    unavailable.add(cart.getName() == null ? "套餐" + cart.getSetmealId() : cart.getName());
                    continue;
                }
                items.add(new ResolvedItem(null, setmeal.getId(), setmeal.getName(), setmeal.getImage(),
                        null, cart.getNumber(), setmeal.getPrice()));
            } else {
                unavailable.add(cart.getName() == null ? "未知商品" : cart.getName());
            }
        }

        if (!unavailable.isEmpty() && strict) {
            log.warn("下单被拒：有 {} 项商品已下架或删除，userId={}，商品={}",
                    unavailable.size(), BaseContext.getCurrentId(), unavailable);
            throw new OrderBusinessException(MessageConstant.ORDER_ITEM_OFF_SALE + "：" + String.join("、", unavailable));
        }
        if (!unavailable.isEmpty()) {
            log.info("已跳过下架商品，userId={}，商品={}", BaseContext.getCurrentId(), unavailable);
        }
        if (items.isEmpty()) {
            if (strict) {
                throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
            }
            throw new OrderBusinessException(MessageConstant.ORDER_ITEM_OFF_SALE);
        }
        return items;
    }

    /**
     * 打包费 = 配置的单份打包费 × 总份数，并设上限。
     * 原来这个数由客户端填（0~100 随便写），而订单总额又照单全收，等于用户自己决定给多少打包费，
     * 与"金额只由服务端计算"的口径直接冲突，所以改成服务端按规则算。
     */
    private BigDecimal calculatePackAmount(List<ResolvedItem> items) {
        int portions = items.stream().mapToInt(ResolvedItem::number).sum();
        BigDecimal fee = orderProperties.getPackFeePerItem()
                .multiply(BigDecimal.valueOf(Math.max(portions, 0)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal max = orderProperties.getPackFeeMax();
        return fee.compareTo(max) > 0 ? max : fee;
    }

    /**
     * 服务端算价：Σ(当前单价 × 数量) + 打包费。
     * 单价取数据库里的现价，不用购物车行的历史快照；不接受任何请求参数里的金额。
     */
    private BigDecimal calculateOrderAmount(List<ResolvedItem> items, BigDecimal packAmount) {
        BigDecimal total = BigDecimal.ZERO;
        for (ResolvedItem item : items) {
            total = total.add(item.price().multiply(BigDecimal.valueOf(item.number())));
        }
        return total.add(packAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 复核过的一行商品：归属 id、名称/图片与单价都来自当前数据库，数量来自购物车。
     */
    private record ResolvedItem(Long dishId, Long setmealId, String name, String image,
                                String dishFlavor, Integer number, BigDecimal price) {
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        // 以订单实际金额为准，不能用写死的测试金额
        Orders ordersDB = orderMapper.getByNumberAndUserId(ordersPaymentDTO.getOrderNumber(), userId);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!Orders.UN_PAID.equals(ordersDB.getPayStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        // 只有"待付款"的订单可以发起支付：超时取消的单 pay_status 仍然是 0，
        // 不判 status 就会让用户对一张已经不存在的订单付钱。
        if (!Orders.PENDING_PAYMENT.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        //调用微信支付接口，生成预支付交易单
        Map<String, Object> payResult = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                ordersDB.getAmount(), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );

        Object code = payResult.get("code");
        if (code != null) {
            log.error("微信统一下单失败，orderNumber={}, 响应={}", ordersPaymentDTO.getOrderNumber(), payResult);
            throw new OrderBusinessException("发起支付失败：" + code);
        }

        OrderPaymentVO vo = new OrderPaymentVO();
        vo.setTimeStamp((String) payResult.get("timeStamp"));
        vo.setNonceStr((String) payResult.get("nonceStr"));
        vo.setSignType((String) payResult.get("signType"));
        vo.setPaySign((String) payResult.get("paySign"));
        vo.setPackageStr((String) payResult.get("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo     商户订单号
     * @param tradeState     微信交易状态，只接受 SUCCESS
     * @param paidAmountFen  回调报文里的实付金额（分），必须与订单应付金额一致才入账
     */
    public void paySuccess(String outTradeNo, String tradeState, Integer paidAmountFen) {
        if (!WECHAT_TRADE_SUCCESS.equals(tradeState)) {
            log.warn("微信回调交易状态非成功，不做处理。outTradeNo={}, tradeState={}", outTradeNo, tradeState);
            return;
        }

        // 回调由微信服务器发起，没有登录态，只能按订单号查，不能用 BaseContext
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 金额校验：验签只证明"消息来自微信"，不证明"金额对得上"。
        // 只付 1 分但订单 50 元的回调必须拒绝入账，否则等于允许差额支付。
        // 与发起支付侧共用同一个换算方法，避免两边口径漂移。
        int expectedFen = WeChatPayUtil.toFen(ordersDB.getAmount());
        if (paidAmountFen == null || paidAmountFen != expectedFen) {
            log.error("支付回调金额与订单金额不一致，拒绝入账。outTradeNo={}, 回调={}分, 订单应付={}分",
                    outTradeNo, paidAmountFen, expectedFen);
            throw new OrderBusinessException(MessageConstant.PAY_AMOUNT_MISMATCH);
        }

        // 条件更新（where pay_status = 0 and status = 1）：微信重复推送时只有一个请求能改成功，天然幂等
        int rows = orderMapper.markPaidByNumber(outTradeNo, LocalDateTime.now());
        if (rows != 1) {
            handleUnmatchedPaidNotify(ordersDB);
            return;
        }

        //通过websocket向客户端浏览器推送消息 type orderId content
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", 1); // 1表示来单提醒 2表示客户催单
        message.put("orderId", ordersDB.getId());
        message.put("content", "订单号：" + outTradeNo);

        webSocketMessagePublisher.broadcast(JsonUtils.toJson(message));

        notificationService.notifyOrder(ordersDB, OrderNotification.TYPE_PAID,
                "支付成功", "订单 " + outTradeNo + " 已支付成功，等待商家接单。");
    }

    /**
     * 收到了成功支付回调但没有推进订单，两种可能：
     * 1. 重复回调 —— 订单已经是已支付/已退款，直接确认，不做二次入账；
     * 2. 迟到支付 —— 订单在用户支付过程中被超时任务取消（status=6、pay_status=0），
     *    钱确实进来了。原来这里只打一行 warn，等于收了钱既不承认也不退，是资损方向。
     *    现在把支付状态留痕并自动发起全额退款，退款本身失败会进补偿队列。
     */
    private void handleUnmatchedPaidNotify(Orders ordersDB) {
        if (Orders.CANCELLED.equals(ordersDB.getStatus()) && Orders.UN_PAID.equals(ordersDB.getPayStatus())) {
            int marked = orderMapper.markLatePayment(ordersDB.getNumber(), LocalDateTime.now());
            if (marked == 1) {
                log.warn("订单已取消但收到支付成功回调，已登记支付并发起自动退款。orderNumber={}", ordersDB.getNumber());
                orderRefundService.applyRefund(ordersDB, "订单已取消，迟到支付自动退款");
                notificationService.notifyOrder(ordersDB, OrderNotification.TYPE_REFUND,
                        "订单已取消，正在退款", "订单 " + ordersDB.getNumber() + " 在取消后完成了支付，系统将全额退回。");
                return;
            }
        }
        log.warn("支付回调未改动订单（重复回调或订单状态已变更），orderNumber={}, status={}, payStatus={}",
                ordersDB.getNumber(), ordersDB.getStatus(), ordersDB.getPayStatus());
    }

    /**
     * 用户端订单分页查询
     *
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 设置分页
        PageHelper.startPage(normalizePage(pageNum), normalizePageSize(pageSize));

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 分页条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList<>();

        // 查询出订单明细，并封装入OrderVO进行响应
        if (page != null && page.getTotal() > 0) {
            // 批量查询所有订单的明细并分组，避免在分页结果上循环单查（N+1）
            Map<Long, List<OrderDetail>> detailMap = loadDetailsGrouped(
                    page.getResult().stream().map(Orders::getId).collect(Collectors.toList()));

            for (Orders orders : page) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(detailMap.getOrDefault(orders.getId(), Collections.emptyList()));
                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(), list);
    }

    /**
     * 查询订单详情
     *
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        // 根据id查询订单
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 查询该订单对应的菜品/套餐明细
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将该订单及其详情封装到OrderVO并返回
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    /**
     * 用户端查询订单详情：必须同时按当前登录用户过滤
     *
     * @param id
     * @return
     */
    public OrderVO userOrderDetails(Long id) {
        Orders orders = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    /**
     * 用户取消订单
     *
     * @param id
     */
    public void userCancelById(Long id) throws Exception {
        Long userId = BaseContext.getCurrentId();
        // 按 id + userId 查，别人的订单查不到
        Orders ordersDB = orderMapper.getByIdAndUserId(id, userId);

        // 校验订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        Integer currentStatus = ordersDB.getStatus();
        if (currentStatus == null || currentStatus > Orders.TO_BE_CONFIRMED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 待接单说明已支付，需要退款；退款状态先跟订单一起落库
        boolean needRefund = Orders.TO_BE_CONFIRMED.equals(currentStatus);
        Integer payStatusAfterCancel = needRefund ? Orders.REFUND : ordersDB.getPayStatus();

        int rows = orderMapper.cancelIfStatusMatches(id, currentStatus, "用户取消", null,
                LocalDateTime.now(), payStatusAfterCancel);
        if (rows != 1) {
            // 并发下订单状态已被改掉（比如刚被商家接单），不能再按旧状态取消
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 先落库再退款：状态确定下来之后才动钱，避免"退款成功但订单没取消"的资损方向
        if (needRefund) {
            notificationService.notifyOrder(ordersDB, OrderNotification.TYPE_REFUND,
                    "订单已取消，退款处理中", "订单 " + ordersDB.getNumber() + " 已取消，退款将原路退回。");
            refundAfterCancelled(ordersDB);
        } else {
            notificationService.notifyOrder(ordersDB, OrderNotification.TYPE_CANCELLED,
                    "订单已取消", "订单 " + ordersDB.getNumber() + " 已取消。");
        }
    }

    /**
     * 订单已取消之后发起退款。退款调用失败不能回滚订单状态（钱可以补，重复取消会造成二次退款），
     * 因此这里不抛出异常，但必须留下可补偿的记录：退款单号由订单号派生并建唯一索引，
     * 失败进入待重试状态，由 RefundCompensator 按退避重投，达到上限才转人工。
     */
    private void refundAfterCancelled(Orders ordersDB) {
        orderRefundService.applyRefund(ordersDB, "订单取消退款");
    }

    /**
     * 再来一单。
     * <p>
     * 原来是把订单明细原样复制进购物车：已下架的菜也能搬进来（要到下单才报错）、
     * 沿用成交时的历史价格（商家改价后按旧价加购）、不与购物车里已有的同款合并、
     * 也不受单行 99 份上限约束。现在一律按当前在售状态与现价重建，并做合并与封顶。
     */
    public void repetition(Long id) {
        // 查询当前用户id
        Long userId = BaseContext.getCurrentId();

        // 只能复用属于自己的订单，否则可以把别人的菜品和价格搬进自己购物车
        if (orderMapper.getByIdAndUserId(id, userId) == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 根据订单id查询当前订单详情
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        if (CollectionUtils.isEmpty(orderDetailList)) {
            return;
        }

        // 明细先转成购物车形状，复用同一套"在售 + 现价"复核；不可售的跳过而不是一律拒绝
        List<ShoppingCart> asCartRows = orderDetailList.stream().map(detail -> {
            ShoppingCart row = new ShoppingCart();
            row.setName(detail.getName());
            row.setImage(detail.getImage());
            row.setDishId(detail.getDishId());
            row.setSetmealId(detail.getSetmealId());
            row.setDishFlavor(detail.getDishFlavor());
            row.setNumber(detail.getNumber() == null || detail.getNumber() < 1 ? 1 : detail.getNumber());
            return row;
        }).collect(Collectors.toList());
        List<ResolvedItem> items = resolveItems(asCartRows, false);

        int maxNumber = orderProperties.getMaxNumberPerItem();

        // 一次查出当前购物车，按 SKU 建索引，避免逐条查询
        ShoppingCart query = new ShoppingCart();
        query.setUserId(userId);
        Map<String, ShoppingCart> existingBySku = new HashMap<>();
        for (ShoppingCart row : shoppingCartMapper.list(query)) {
            existingBySku.putIfAbsent(skuKey(row.getDishId(), row.getSetmealId(), row.getDishFlavor()), row);
        }

        // 新增行先在内存里按 SKU 合并：同一个 SKU 两条一起批量插入会撞购物车的唯一索引
        Map<String, ShoppingCart> pendingInsert = new LinkedHashMap<>();
        for (ResolvedItem item : items) {
            String key = skuKey(item.dishId(), item.setmealId(), item.dishFlavor());
            ShoppingCart existing = existingBySku.get(key);
            if (existing != null) {
                // 数量累加与上限判断都交给 SQL，返回 0 说明加不完，直接封顶到上限
                int rows = shoppingCartMapper.increaseNumberByDelta(existing.getId(),
                        item.number(), maxNumber);
                if (rows != 1) {
                    shoppingCartMapper.capNumberById(existing.getId(), maxNumber);
                    log.info("购物车商品 {} 数量已达上限 {}，按上限封顶", item.name(), maxNumber);
                }
                continue;
            }
            ShoppingCart pending = pendingInsert.get(key);
            if (pending != null) {
                pending.setNumber(Math.min(pending.getNumber() + item.number(), maxNumber));
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            pendingInsert.put(key, ShoppingCart.builder()
                    .name(item.name())
                    .image(item.image())
                    .dishId(item.dishId())
                    .setmealId(item.setmealId())
                    .dishFlavor(item.dishFlavor())
                    .number(Math.min(item.number(), maxNumber))
                    // 价格取复核后的现价，不是订单快照价
                    .amount(item.price())
                    .userId(userId)
                    .createTime(now)
                    .build());
        }

        if (!pendingInsert.isEmpty()) {
            shoppingCartMapper.insertBatch(new ArrayList<>(pendingInsert.values()));
        }
    }

    /**
     * 购物车里"同一个商品"的判定口径：菜品按 dishId + 口味，套餐按 setmealId。
     * 与 shopping_cart 的唯一索引（item_key 生成列）保持一致。
     */
    private static String skuKey(Long dishId, Long setmealId, String dishFlavor) {
        if (dishId != null) {
            return "D" + dishId + ":" + (dishFlavor == null ? "" : dishFlavor);
        }
        return "S" + setmealId + ":";
    }

    /**
     * 订单搜索
     *
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(normalizePage(ordersPageQueryDTO.getPage()),
                normalizePageSize(ordersPageQueryDTO.getPageSize()));

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 部分订单状态，需要额外返回订单菜品信息，将Orders转化为OrderVO
        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }

    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        // 需要返回订单菜品信息，自定义OrderVO响应结果
        List<OrderVO> orderVOList = new ArrayList<>();

        List<Orders> ordersList = page.getResult();
        if (CollectionUtils.isEmpty(ordersList)) {
            return orderVOList;
        }

        // 一次批量查明细再分组，避免每个订单一次查询（管理端列表的 N+1）
        Map<Long, List<OrderDetail>> detailMap = loadDetailsGrouped(
                ordersList.stream().map(Orders::getId).collect(Collectors.toList()));

        for (Orders orders : ordersList) {
            OrderVO orderVO = new OrderVO();
            BeanUtils.copyProperties(orders, orderVO);
            orderVO.setOrderDishes(getOrderDishesStr(detailMap.getOrDefault(orders.getId(), Collections.emptyList())));
            orderVOList.add(orderVO);
        }
        return orderVOList;
    }

    private Map<Long, List<OrderDetail>> loadDetailsGrouped(List<Long> orderIds) {
        if (CollectionUtils.isEmpty(orderIds)) {
            return Collections.emptyMap();
        }
        return orderDetailMapper.getByOrderIds(orderIds).stream()
                .collect(Collectors.groupingBy(OrderDetail::getOrderId));
    }

    /**
     * 把订单明细拼成"宫保鸡丁*3；"这样的字符串
     *
     * @param orderDetailList
     * @return
     */
    private String getOrderDishesStr(List<OrderDetail> orderDetailList) {
        List<String> orderDishList = orderDetailList.stream()
                .map(x -> x.getName() + "*" + x.getNumber() + ";")
                .collect(Collectors.toList());

        // 将该订单对应的所有菜品信息拼接在一起
        return String.join("", orderDishList);
    }

    /**
     * 各个状态的订单数量统计
     *
     * @return
     */
    public OrderStatisticsVO statistics() {
        // 根据状态，分别查询出待接单、待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 将查询出的数据封装到orderStatisticsVO中响应
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 接单：待接单(2) -> 已接单(3)。带前置状态的条件更新，避免并发重复接单。
     *
     * @param ordersConfirmDTO
     */
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        int rows = orderMapper.updateStatus(ordersConfirmDTO.getId(),
                Orders.TO_BE_CONFIRMED, Orders.CONFIRMED);
        if (rows != 1) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        notifyAfterTransition(ordersConfirmDTO.getId(), OrderNotification.TYPE_CONFIRMED,
                "商家已接单", "商家已接单，正在备餐。");
    }

    /**
     * 状态推进成功后给用户留一条站内通知。
     * 原来只有支付和催单走 WebSocket 广播，接单/拒单/派送/完成对用户是静默发生的；
     * 而 WebSocket 连接（sid）没有和用户绑定，也无法定向推送。
     */
    private void notifyAfterTransition(Long orderId, int type, String title, String content) {
        Orders order = orderMapper.getById(orderId);
        if (order != null) {
            notificationService.notifyOrder(order, type, title, content);
        }
    }

    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());

        // 订单只有存在且状态为2（待接单）才可以拒单
        if (ordersDB == null || !Orders.TO_BE_CONFIRMED.equals(ordersDB.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        boolean needRefund = Orders.PAID.equals(ordersDB.getPayStatus());

        // 先把取消状态落库（带前置状态条件），再退款
        int rows = orderMapper.cancelIfStatusMatches(ordersDB.getId(), Orders.TO_BE_CONFIRMED,
                null, ordersRejectionDTO.getRejectionReason(), LocalDateTime.now(),
                needRefund ? Orders.REFUND : ordersDB.getPayStatus());
        if (rows != 1) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        if (needRefund) {
            notificationService.notifyOrder(ordersDB, OrderNotification.TYPE_REJECTED,
                    "商家已拒单", "商家取消了订单：" + nullToEmpty(ordersRejectionDTO.getRejectionReason())
                            + "，退款将原路退回。");
            refundAfterCancelled(ordersDB);
        } else {
            notificationService.notifyOrder(ordersDB, OrderNotification.TYPE_REJECTED,
                    "商家已拒单", "商家取消了订单：" + nullToEmpty(ordersRejectionDTO.getRejectionReason()));
        }
    }

    /**
     * 取消订单
     *
     * @param ordersCancelDTO
     */
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 已完成(5)/已取消(6)的订单不能再取消，否则可以对同一笔订单反复发起退款
        Integer currentStatus = ordersDB.getStatus();
        if (currentStatus == null || currentStatus >= Orders.COMPLETED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        boolean needRefund = Orders.PAID.equals(ordersDB.getPayStatus());

        int rows = orderMapper.cancelIfStatusMatches(ordersDB.getId(), currentStatus,
                ordersCancelDTO.getCancelReason(), null, LocalDateTime.now(),
                needRefund ? Orders.REFUND : ordersDB.getPayStatus());
        if (rows != 1) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        if (needRefund) {
            notificationService.notifyOrder(ordersDB, OrderNotification.TYPE_CANCELLED,
                    "订单已取消", "订单已取消：" + nullToEmpty(ordersCancelDTO.getCancelReason())
                            + "，退款将原路退回。");
            refundAfterCancelled(ordersDB);
        } else {
            notificationService.notifyOrder(ordersDB, OrderNotification.TYPE_CANCELLED,
                    "订单已取消", "订单已取消：" + nullToEmpty(ordersCancelDTO.getCancelReason()));
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 派送订单：已接单(3) -> 派送中(4)。带前置状态的条件更新，避免并发重复派送。
     *
     * @param id
     */
    public void delivery(Long id) {
        // 同时记录"开始派送时间"：派送超时的判定基准是它，不是下单时间
        int rows = orderMapper.startDelivery(id, Orders.CONFIRMED, Orders.DELIVERY_IN_PROGRESS,
                LocalDateTime.now());
        if (rows != 1) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        notifyAfterTransition(id, OrderNotification.TYPE_DELIVERING, "订单已送出", "订单已发货，正在派送中。");
    }

    /**
     * 完成订单：派送中(4) -> 已完成(5)。带前置状态的条件更新，避免并发重复完成。
     *
     * @param id
     */
    public void complete(Long id) {
        int rows = orderMapper.completeByIdAndExpectedStatus(id,
                Orders.DELIVERY_IN_PROGRESS, Orders.COMPLETED, LocalDateTime.now());
        if (rows != 1) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        notifyAfterTransition(id, OrderNotification.TYPE_COMPLETED, "订单已完成", "订单已送达，感谢你的光临。");
    }

    /**
     * 客户催单
     * @param id
     */
    public void reminder(Long id) {
        // 只能催自己的订单
        Orders ordersDB = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());

        // 校验订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", 2); //1表示来单提醒 2表示客户催单
        message.put("orderId", id);
        message.put("content", "订单号：" + ordersDB.getNumber());

        //通过websocket向客户端浏览器推送消息
        webSocketMessagePublisher.broadcast(JsonUtils.toJson(message));
    }

    private static int normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    private static int normalizePageSize(int pageSize) {
        if (pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
