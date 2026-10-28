package com.sky.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 按天聚合的统计数据。
 * <p>
 * 报表原来逐日循环查询：30 天的导出要 31×4 次查询，最长 366 天的区间更是要上千次。
 * 这几项的口径都是"按 date(order_time) 分组"，一条 group by 就能出全部，
 * 所以用这一个 DTO 承载不同查询的结果（各查询只填自己那几列）。
 */
@Data
public class DailyStatisticsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 统计日 */
    private LocalDate date;

    /** 当日已完成订单金额合计（营业额） */
    private Double turnover;

    /** 当日订单总数（全部状态） */
    private Integer totalOrders;

    /** 当日有效订单数（已完成） */
    private Integer validOrders;

    /** 当日新增用户数 */
    private Integer newUsers;
}
