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

    /**
     * 还有多少历史订单明细在用这张图。
     * 这是"能不能真的删掉 OSS 对象"最关键的一条：明细里存的是图片 URL 快照，
     * 用户回看历史订单时还要看图，删了对象就是 404。
     */
    @Select("select count(1) from order_detail where image = #{image}")
    int countByImage(@Param("image") String image);
}
