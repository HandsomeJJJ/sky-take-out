package com.sky.mapper;

import com.sky.dto.DailyStatisticsDTO;
import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {

    /**
     * 根据openid查询用户
     * @param openid
     * @return
     */
    @Select("select * from user where openid = #{openid}")
    User getByOpenid(String openid);

    /**
     * 插入数据
     * @param user
     */
    void insert(User user);

    @Select("select * from user where id = #{id}")
    User getById(Long userId);

    /**
     * 根据动态条件统计用户数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);

    /**
     * 截止某一时刻的累计用户数（半开区间的左端点取值）。
     * 报表里的"总用户数"是存量而不是当日增量，原来靠逐日重复 count 得到，
     * 现在取一次起点 + 每日新增累加即可。
     */
    Integer countUntil(@Param("end") LocalDateTime end);

    /** 按天聚合新增用户数，替代逐日 count 循环 */
    List<DailyStatisticsDTO> dailyNewUsers(@Param("begin") LocalDateTime begin,
                                           @Param("end") LocalDateTime end);
}
