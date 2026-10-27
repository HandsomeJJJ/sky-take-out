package com.sky.service;

import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 报表统计的日期区间守卫：这里曾经是 while(!begin.equals(end)) 的死循环入口。
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
    @DisplayName("正常区间逐日展开，营业额按天返回")
    void expandsRangeDayByDay() {
        when(orderMapper.sumByMap(any())).thenReturn(null);

        var vo = reportService.getTurnoverStatistics(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));

        assertThat(vo.getDateList()).isEqualTo("2026-10-01,2026-10-02,2026-10-03");
        assertThat(vo.getTurnoverList()).isEqualTo("0.0,0.0,0.0");
    }
}
