package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.GoodsSalesDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
    @Select("select * from orders where status = #{status} and order_time < #{orderTime}")
    List<Orders> getByStatusAndOrderTimeLT(Integer status, LocalDateTime orderTime);

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
