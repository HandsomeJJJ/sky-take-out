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

    /** 标记已发送：status 条件保证并发下只成功一次 */
    @Update("update mq_message set status = 1, update_time = now() where id = #{id} and status = 0")
    int markSent(@Param("id") Long id);

    /** 发送失败：重试次数 +1，退避到 nextRetryTime */
    @Update("update mq_message set retry_count = retry_count + 1, next_retry_time = #{nextRetryTime}, update_time = now() " +
            "where id = #{id}")
    int incrRetry(@Param("id") Long id, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /** 超过重试上限，标记死亡待人工处理 */
    @Update("update mq_message set status = 2, update_time = now() where id = #{id} and retry_count >= #{maxRetry}")
    int markDead(@Param("id") Long id, @Param("maxRetry") int maxRetry);
}
