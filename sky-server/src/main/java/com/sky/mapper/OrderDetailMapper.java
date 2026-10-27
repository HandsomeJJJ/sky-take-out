package com.sky.mapper;

import com.sky.entity.OrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderDetailMapper {
    /**
     * 批量插入订单明细数据
     * @param orderDetailList
     */
    void insertBatch(List<OrderDetail> orderDetailList);

    /**
     * 根据订单id查询订单明细
     * @param orderId
     * @return
     */
    @Select("select * from order_detail where order_id = #{orderId}")
    List<OrderDetail> getByOrderId(Long orderId);

    /**
     * 批量查询订单明细，避免在分页结果上循环单查（N+1 问题）。
     */
    @Select("<script>select * from order_detail where order_id in " +
            "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<OrderDetail> getByOrderIds(@Param("orderIds") List<Long> orderIds);
}
