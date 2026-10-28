package com.sky.mapper;

import com.sky.entity.OrderRefund;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderRefundMapper {

    /**
     * 按退款单号建记录。唯一索引 + on duplicate do nothing：
     * 同一笔订单第二次申请退款时不会新建记录，也不会把已成功/进行中的状态倒回去，
     * 这就是"重复退款"的防线，不靠调用前查一次（查完再写之间有竞态）。
     */
    @Insert("insert into order_refund (order_id, order_number, refund_no, amount, status, retry_count, " +
            "next_retry_time, reason, create_time, update_time) " +
            "values (#{orderId}, #{orderNumber}, #{refundNo}, #{amount}, #{status}, 0, " +
            "#{nextRetryTime}, #{reason}, #{createTime}, #{updateTime}) " +
            "on duplicate key update id = id")
    int insertIfAbsent(OrderRefund refund);

    @Select("select * from order_refund where refund_no = #{refundNo}")
    OrderRefund getByRefundNo(String refundNo);

    @Select("select * from order_refund where order_id = #{orderId}")
    List<OrderRefund> getByOrderId(Long orderId);

    /** 只有还没成功的记录才允许被改状态，回调重复投递天然幂等 */
    @Update("update order_refund set status = #{status}, wechat_refund_id = #{wechatRefundId}, " +
            "last_error = #{lastError}, update_time = #{updateTime} " +
            "where refund_no = #{refundNo} and status not in (2, 4)")
    int markResult(@Param("refundNo") String refundNo, @Param("status") Integer status,
                   @Param("wechatRefundId") String wechatRefundId, @Param("lastError") String lastError,
                   @Param("updateTime") LocalDateTime updateTime);

    /**
     * 记一次失败：递增重试次数并把下次重试时间推后。
     * 超过上限直接置为"需人工处理"，不再无限占用补偿窗口。
     */
    @Update("update order_refund set status = #{status}, retry_count = retry_count + 1, " +
            "next_retry_time = #{nextRetryTime}, last_error = #{lastError}, update_time = #{updateTime} " +
            "where refund_no = #{refundNo} and status not in (2, 4)")
    int markRetry(@Param("refundNo") String refundNo, @Param("status") Integer status,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime, @Param("lastError") String lastError,
                  @Param("updateTime") LocalDateTime updateTime);

    /**
     * 待补偿记录：待发起与失败都要重投，按 next_retry_time 升序取一批。
     * 必须带 limit，且状态条件保证同一行不会被子任务重复捞起后并发处理。
     */
    @Select("select * from order_refund where status in (0, 3) and next_retry_time <= #{now} " +
            "order by next_retry_time asc limit #{limit}")
    List<OrderRefund> findRetryable(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
