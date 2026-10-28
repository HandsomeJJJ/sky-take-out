package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.time.LocalDateTime;

/**
 * 店铺营业状态。单行表（id 固定为 1）：状态是全局唯一的业务开关，
 * 只存 Redis 的话一次缓存丢失就会把"打烊"变成"营业中"。
 */
@Mapper
public interface ShopStatusMapper {

    @Select("select status from shop_status where id = 1")
    Integer getStatus();

    /**
     * 没有记录就初始化，有记录就覆盖。营业状态的写入天然是幂等的 upsert。
     */
    @Insert("insert into shop_status (id, status, update_time) values (1, #{status}, #{updateTime}) " +
            "on duplicate key update status = #{status}, update_time = #{updateTime}")
    int upsert(@Param("status") Integer status, @Param("updateTime") LocalDateTime updateTime);
}
