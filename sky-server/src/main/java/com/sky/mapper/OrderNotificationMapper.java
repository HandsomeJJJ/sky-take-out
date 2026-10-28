package com.sky.mapper;

import com.sky.entity.OrderNotification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderNotificationMapper {

    @Insert("insert into order_notification (user_id, order_id, type, title, content, read_flag, create_time) " +
            "values (#{userId}, #{orderId}, #{type}, #{title}, #{content}, 0, #{createTime})")
    void insert(OrderNotification notification);

    /**
     * 只查自己的通知：user_id 来自登录态而不是入参，避免读到别人的订单动态。
     */
    @Select("select * from order_notification where user_id = #{userId} order by create_time desc limit #{limit}")
    List<OrderNotification> listByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("select count(1) from order_notification where user_id = #{userId} and read_flag = 0")
    int countUnread(Long userId);

    @Update("update order_notification set read_flag = 1 where user_id = #{userId} and read_flag = 0")
    int markAllRead(Long userId);
}
