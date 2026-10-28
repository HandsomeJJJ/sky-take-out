package com.sky.mapper;

import com.sky.entity.MediaObject;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对象存储文件登记表的访问。删除动作永远带状态条件，保证多实例并发回收时
 * 同一个对象只会被删一次。
 */
@Mapper
public interface MediaObjectMapper {

    /** 上传成功就登记；同一个对象名重复登记直接忽略（UUID 命名理论上不会撞） */
    @Insert("insert ignore into media_object (object_name, url, status, create_time, update_time) " +
            "values (#{objectName}, #{url}, 0, #{createTime}, #{updateTime})")
    int register(@Param("objectName") String objectName, @Param("url") String url,
                 @Param("createTime") LocalDateTime createTime, @Param("updateTime") LocalDateTime updateTime);

    /** 标记待回收：只有在用状态 0 -> 1 */
    @Update("update media_object set status = 1, retire_time = #{retireTime}, update_time = #{updateTime} " +
            "where url = #{url} and status = 0")
    int markRetiring(@Param("url") String url, @Param("retireTime") LocalDateTime retireTime,
                     @Param("updateTime") LocalDateTime updateTime);

    /** 又有地方引用了这张图（例如另一道菜填了同一个 URL），撤回待回收标记 */
    @Update("update media_object set status = 0, retire_time = null, update_time = #{updateTime} " +
            "where url = #{url} and status = 1")
    int reactivate(@Param("url") String url, @Param("updateTime") LocalDateTime updateTime);

    /** 到期且仍待回收的记录 */
    @Select("select * from media_object where status = 1 and retire_time is not null " +
            "and retire_time <= #{dueBefore} order by retire_time asc limit #{limit}")
    List<MediaObject> findDueForDeletion(@Param("dueBefore") LocalDateTime dueBefore, @Param("limit") int limit);

    /** 领取删除：1 -> 2，只有抢到的那一次才真的去调 OSS */
    @Update("update media_object set status = 2, update_time = #{updateTime} where id = #{id} and status = 1")
    int claimDeletion(@Param("id") Long id, @Param("updateTime") LocalDateTime updateTime);

    @Select("select * from media_object where url = #{url}")
    MediaObject getByUrl(@Param("url") String url);
}
