package com.sky.mapper;

import com.sky.entity.MqMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MqMessageMapper {

    @Insert("insert into mq_message(biz_type, biz_id, payload, status, retry_count, next_retry_time, create_time, update_time) " +
            "values(#{bizType}, #{bizId}, #{payload}, #{status}, #{retryCount}, #{nextRetryTime}, #{createTime}, #{updateTime})")
    void insert(MqMessage message);

    /** 查询待发送且到期的消息，分批拉取避免一次扫全表 */
    @Select("select * from mq_message where status = #{status} and next_retry_time <= now() order by id asc limit #{limit}")
    List<MqMessage> listPending(@Param("status") int status, @Param("limit") int limit);

    /**
     * 按业务类型查询待发送消息：发送锁按目标中间件拆分之后，每个锁只处理自己那类消息，
     * 这样 RabbitMQ 慢不会影响 Kafka 通道的推进，反之也一样。
     */
    @Select("<script>select * from mq_message where status = #{status} and next_retry_time &lt;= now() " +
            "and biz_type in <foreach collection=\"bizTypes\" item=\"t\" open=\"(\" separator=\",\" close=\")\">#{t}</foreach> " +
            "order by id asc limit #{limit}</script>")
    List<MqMessage> listPendingByBizTypes(@Param("status") int status,
                                          @Param("bizTypes") List<String> bizTypes,
                                          @Param("limit") int limit);

    /**
     * 认领：把待发送记录原子地占为"发送中"。
     * 集群里多个实例扫到同一批记录时，只有一个实例的 update 影响行数是 1，
     * 其余实例直接跳过 —— 没有这一步，每条消息会被投递 N 次（N = 实例数）。
     */
    @Update("update mq_message set status = 3, update_time = now() where id = #{id} and status = 0")
    int claim(@Param("id") Long id);

    /**
     * 回收卡死的"发送中"记录：持有者进程崩溃后没人把它改回去，
     * 超过阈值就退回待发送，否则这条消息永远不会再被补偿。
     */
    @Update("update mq_message set status = 0, next_retry_time = now(), update_time = now() " +
            "where status = 3 and update_time < #{staleBefore}")
    int reclaimStuck(@Param("staleBefore") LocalDateTime staleBefore);

    /** 标记已发送：只接受"发送中"的记录，保证认领者才能收尾 */
    @Update("update mq_message set status = 1, update_time = now() where id = #{id} and status = 3")
    int markSent(@Param("id") Long id);

    /** 发送失败：退回待发送、重试次数 +1、退避到 nextRetryTime */
    @Update("update mq_message set status = 0, retry_count = retry_count + 1, next_retry_time = #{nextRetryTime}, " +
            "update_time = now() where id = #{id} and status = 3")
    int incrRetry(@Param("id") Long id, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /** 超过重试上限，标记死亡待人工处理 */
    @Update("update mq_message set status = 2, update_time = now() where id = #{id} and retry_count >= #{maxRetry}")
    int markDead(@Param("id") Long id, @Param("maxRetry") int maxRetry);
}
