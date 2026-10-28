package com.sky.mapper;

import com.sky.entity.DishFlavor;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishFlavorMapper {
    /**
     * 批量插入口味数据
     * @param flavors
     */
    void insertBatch(List<DishFlavor> flavors);

    /**
     * 根据菜品id删除对应的口味数据
     * @param dishId
     */
    @Delete("delete from dish_flavor where dish_id = #{dishId}")
    void deleteByDishId(Long dishId);

    /**
     * 根据菜品id查询对应的口味数据
     * @param dishId
     * @return
     */
    @Select("select * from dish_flavor where dish_id = #{dishId}")
    List<DishFlavor> getByDishId(Long dishId);

    /**
     * 按菜品 id 批量查口味：C 端浏览菜单要把一批菜品的口味一起带回来，
     * 逐个菜品单查就是 N+1。
     */
    @Select("<script>select * from dish_flavor where dish_id in " +
            "<foreach collection='dishIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<DishFlavor> getByDishIds(@Param("dishIds") List<Long> dishIds);
}
