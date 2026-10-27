package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.GoodsSalesDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    /**
     * 插入订单数据
     * @param orders
     */
    void insert(Orders orders);

    /**
     * 根据订单号和用户id查询订单
     * @param orderNumber
     * @param userId
     */
    @Select("select * from orders where number = #{orderNumber} and user_id= #{userId}")
    Orders getByNumberAndUserId(String orderNumber, Long userId);

    /**
     * 仅按订单号查询。支付回调由微信服务器发起，没有登录态，不能带 userId。
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /**
     * 修改订单信息
     * @param orders
     */
    void update(Orders orders);

    /**
     * 分页条件查询并按下单时间排序
     * @param ordersPageQueryDTO
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 根据id查询订单
     * @param id
     */
    @Select("select * from orders where id=#{id}")
    Orders getById(Long id);

    /**
     * 按 id + userId 查询订单：用户端所有订单操作都必须用它，
     * 只按 id 查等于把"订单归属"交给客户端自觉，可以遍历 id 看/改别人的订单。
     */
    @Select("select * from orders where id = #{id} and user_id = #{userId}")
    Orders getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 支付回调专用：只允许"待支付且未支付"的订单入账。
     * 微信会重复推送，条件更新保证只有一个请求能改成功，不需要额外的锁。
     * 必须同时限定 status = 1：超时任务取消的订单 pay_status 仍是 0，
     * 只判 pay_status 会让一笔迟到支付把已取消订单直接改回待接单。
     */
    @Update("update orders set status = 2, pay_status = 1, checkout_time = #{checkoutTime} " +
            "where number = #{number} and pay_status = 0 and status = 1")
    int markPaidByNumber(@Param("number") String number, @Param("checkoutTime") LocalDateTime checkoutTime);

    /**
     * 取消订单（乐观锁）：仅当状态仍是 expectedStatus 才生效，同时写入取消/拒单原因与支付状态。
     * 返回 0 表示订单已被其他人改过状态，调用方按"状态错误"处理。
     */
    @Update("update orders set status = 6, cancel_reason = #{cancelReason}, rejection_reason = #{rejectionReason}, " +
            "cancel_time = #{cancelTime}, pay_status = #{payStatus} " +
            "where id = #{id} and status = #{expectedStatus}")
    int cancelIfStatusMatches(@Param("id") Long id, @Param("expectedStatus") Integer expectedStatus,
                              @Param("cancelReason") String cancelReason,
                              @Param("rejectionReason") String rejectionReason,
                              @Param("cancelTime") LocalDateTime cancelTime,
                              @Param("payStatus") Integer payStatus);

    /**
     * 根据状态统计订单数量
     * @param status
     */
    @Select("select count(id) from orders where status = #{status}")
    Integer countStatus(Integer status);

    /**
     * 根据订单状态和下单时间查询订单
     * @param status
     * @param orderTime
     * @return
     */
    /**
     * 按状态与时间扫描待处理订单。必须带 limit：不加上限时一次全表扫描会把
     * 所有积压订单装进内存，并且这个查询每分钟都在跑。
     */
    @Select("select * from orders where status = #{status} and order_time < #{orderTime} " +
            "order by order_time asc limit #{limit}")
    List<Orders> getByStatusAndOrderTimeLT(@Param("status") Integer status,
                                           @Param("orderTime") LocalDateTime orderTime,
                                           @Param("limit") int limit);

    /**
     * 仅取消待支付订单。status 条件使消息重复投递时仍然幂等。
     */
    @org.apache.ibatis.annotations.Update("update orders set status = 6, cancel_reason = #{cancelReason}, cancel_time = #{cancelTime} " +
            "where id = #{id} and status = 1")
    int cancelPendingPaymentById(@Param("id") Long id, @Param("cancelReason") String cancelReason,
                                 @Param("cancelTime") LocalDateTime cancelTime);

    /**
     * 带前置状态的状态推进（乐观锁）：仅当当前状态等于 expectedStatus 才更新。
     * 并发下两个请求同时接单/派送，只有一个能成功，避免重复更新。
     */
    @org.apache.ibatis.annotations.Update("update orders set status = #{newStatus} " +
            "where id = #{id} and status = #{expectedStatus}")
    int updateStatus(@Param("id") Long id, @Param("expectedStatus") Integer expectedStatus,
                     @Param("newStatus") Integer newStatus);

    /**
     * 完成订单：同样带前置状态条件，同时写入送达时间。
     */
    @org.apache.ibatis.annotations.Update("update orders set status = #{newStatus}, delivery_time = #{deliveryTime} " +
            "where id = #{id} and status = #{expectedStatus}")
    int completeByIdAndExpectedStatus(@Param("id") Long id, @Param("expectedStatus") Integer expectedStatus,
                                      @Param("newStatus") Integer newStatus,
                                      @Param("deliveryTime") LocalDateTime deliveryTime);

    /**
     * 根据动态条件统计营业额数据
     * @param map
     * @return
     */
    Double sumByMap(Map map);

    /**
     * 根据动态条件统计订单数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);

    /**
     * 统计指定时间区间内的销量排名前10
     * @param begin
     * @param end
     * @return
     */
    List<GoodsSalesDTO> getSalesTop10(LocalDateTime begin,LocalDateTime end);
}
