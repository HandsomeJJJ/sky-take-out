package com.sky.service;

import com.sky.dto.DailyStatisticsDTO;
import com.sky.dto.GoodsSalesDTO;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表统计：日期区间守卫 + 按天聚合的口径。
 * <p>
 * 这里曾经是 while(!begin.equals(end)) 的死循环入口，也是"逐日循环查询"的重灾区，
 * 两条都靠测试锁住。
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private WorkspaceService workspaceService;

    @InjectMocks
    private ReportServiceImpl reportService;

    @Test
    @DisplayName("开始日期晚于结束日期时直接拒绝，不再无限循环")
    void rejectsReversedRange() {
        LocalDate begin = LocalDate.of(2026, 10, 20);
        LocalDate end = LocalDate.of(2026, 10, 1);

        assertThatThrownBy(() -> reportService.getTurnoverStatistics(begin, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能晚于");

        assertThatThrownBy(() -> reportService.getOrderStatistics(begin, end))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> reportService.getUserStatistics(begin, end))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> reportService.getSalesTop10(begin, end))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("区间跨度超过上限时拒绝")
    void rejectsOverWideRange() {
        LocalDate begin = LocalDate.of(2020, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() -> reportService.getTurnoverStatistics(begin, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多");
    }

    @Test
    @DisplayName("空参数被拒绝，而不是拆箱 NPE")
    void rejectsNullRange() {
        assertThatThrownBy(() -> reportService.getTurnoverStatistics(null, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("逐日展开按天聚合结果：没有订单的日期补 0，整段只查一次")
    void expandsRangeDayByDay() {
        LocalDate begin = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 3);
        when(orderMapper.dailyOrderStatistics(any(), any()))
                .thenReturn(List.of(daily(LocalDate.of(2026, 10, 2), 50.0, 4, 3)));

        var vo = reportService.getTurnoverStatistics(begin, end);

        assertThat(vo.getDateList()).isEqualTo("2026-10-01,2026-10-02,2026-10-03");
        assertThat(vo.getTurnoverList()).isEqualTo("0.0,50.0,0.0");

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderMapper).dailyOrderStatistics(captor.capture(), captor.capture());
        assertThat(captor.getAllValues())
                .containsExactly(begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("订单区间是半开 [begin, end+1)：结束日当天的订单要算进来")
    void usesHalfOpenInterval() {
        LocalDate begin = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 1);
        when(orderMapper.dailyOrderStatistics(eq(begin.atStartOfDay()), eq(end.plusDays(1).atStartOfDay())))
                .thenReturn(List.of(daily(begin, 100.0, 5, 5)));

        var vo = reportService.getOrderStatistics(begin, end);

        assertThat(vo.getOrderCountList()).isEqualTo("5");
        assertThat(vo.getValidOrderCountList()).isEqualTo("5");
    }

    @Test
    @DisplayName("订单完成率按区间汇总后的有效单/总单计算")
    void computesCompletionRate() {
        LocalDate begin = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 2);
        when(orderMapper.dailyOrderStatistics(any(), any())).thenReturn(List.of(
                daily(begin, 10.0, 4, 2),
                daily(end, 10.0, 4, 2)));

        var vo = reportService.getOrderStatistics(begin, end);

        assertThat(vo.getTotalOrderCount()).isEqualTo(8);
        assertThat(vo.getValidOrderCount()).isEqualTo(4);
        assertThat(vo.getOrderCompletionRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("总用户数取起点存量再加每日增量，不再逐日全表 count")
    void accumulatesTotalUsersFromStartingStock() {
        LocalDate begin = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 3);
        when(userMapper.countUntil(begin.atStartOfDay())).thenReturn(100);
        when(userMapper.dailyNewUsers(any(), any())).thenReturn(List.of(
                newUsers(begin, 5),
                newUsers(LocalDate.of(2026, 10, 3), 2)));

        var vo = reportService.getUserStatistics(begin, end);

        assertThat(vo.getNewUserList()).isEqualTo("5,0,2");
        assertThat(vo.getTotalUserList()).isEqualTo("105,105,107");
    }

    @Test
    @DisplayName("销量 Top10 用聚合查询结果原名返回，名称不再作为分组键的口径交给 SQL")
    void joinsSalesTop10FromGroupedQuery() {
        LocalDate begin = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 7);
        when(orderMapper.getSalesTop10(begin.atStartOfDay(), end.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(new GoodsSalesDTO("美式", 12), new GoodsSalesDTO("拿铁", 7)));

        var vo = reportService.getSalesTop10(begin, end);

        assertThat(vo.getNameList()).isEqualTo("美式,拿铁");
        assertThat(vo.getNumberList()).isEqualTo("12,7");
    }

    @Test
    @DisplayName("聚合结果为空时逐日补 0，而不是返回空串")
    void fillsZeroWhenNoData() {
        LocalDate begin = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 2);
        when(orderMapper.dailyOrderStatistics(any(), any())).thenReturn(Collections.emptyList());
        when(userMapper.countUntil(any())).thenReturn(0);
        when(userMapper.dailyNewUsers(any(), any())).thenReturn(Collections.emptyList());

        assertThat(reportService.getTurnoverStatistics(begin, end).getTurnoverList()).isEqualTo("0.0,0.0");
        assertThat(reportService.getOrderStatistics(begin, end).getOrderCountList()).isEqualTo("0,0");
        assertThat(reportService.getUserStatistics(begin, end).getTotalUserList()).isEqualTo("0,0");
    }

    private static DailyStatisticsDTO daily(LocalDate date, Double turnover, Integer total, Integer valid) {
        DailyStatisticsDTO dto = new DailyStatisticsDTO();
        dto.setDate(date);
        dto.setTurnover(turnover);
        dto.setTotalOrders(total);
        dto.setValidOrders(valid);
        return dto;
    }

    private static DailyStatisticsDTO newUsers(LocalDate date, Integer count) {
        DailyStatisticsDTO dto = new DailyStatisticsDTO();
        dto.setDate(date);
        dto.setNewUsers(count);
        return dto;
    }
}
