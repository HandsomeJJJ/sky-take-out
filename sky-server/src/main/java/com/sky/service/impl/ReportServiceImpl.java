package com.sky.service.impl;

import com.sky.dto.DailyStatisticsDTO;
import com.sky.dto.GoodsSalesDTO;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkspaceService workspaceService;

    /** 运营数据报表模板在 classpath 下的位置 */
    private static final String EXCEL_TEMPLATE = "template/运营数据报表模板.xlsx";

    /** 导出最近多少天 */
    private static final int EXPORT_DAYS = 30;

    /** 报表一次最多统计的天数，避免请求把几年数据拉进内存 */
    private static final int MAX_STATISTICS_DAYS = 366;

    /**
     * 统计指定时间区间内的营业额数据
     *
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = buildDateList(begin, end);
        Map<LocalDate, DailyStatisticsDTO> byDate = dailyOrderStatistics(begin, end);

        // 营业额只算已完成订单，口径与 sumByMap(status=5) 一致
        List<Double> turnoverList = dateList.stream()
                .map(date -> turnoverOf(byDate.get(date)))
                .collect(Collectors.toList());

        return TurnoverReportVO
                .builder()
                .dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的用户数据
     *
     * @param begin
     * @param end
     * @return
     */
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = buildDateList(begin, end);
        Map<LocalDate, Integer> newUsersByDate = dailyNewUsers(begin, end);

        List<Integer> newUserList = new ArrayList<>();
        List<Integer> totalUserList = new ArrayList<>();

        // 总用户数是"当天为止的存量"：取区间起点的累计值，再逐日加当天新增。
        // 原来每天都单独 count 一次全表（30 天 = 30 次全表 count），口径一样但成本高得多。
        int runningTotal = userMapper.countUntil(begin.atStartOfDay());
        for (LocalDate date : dateList) {
            int newUsers = newUsersByDate.getOrDefault(date, 0);
            runningTotal += newUsers;
            newUserList.add(newUsers);
            totalUserList.add(runningTotal);
        }

        return UserReportVO
                .builder()
                .dateList(StringUtils.join(dateList, ","))
                .totalUserList(StringUtils.join(totalUserList, ","))
                .newUserList(StringUtils.join(newUserList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的订单数据
     * @param begin
     * @param end
     * @return
     */
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = buildDateList(begin, end);
        Map<LocalDate, DailyStatisticsDTO> byDate = dailyOrderStatistics(begin, end);

        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();
        for (LocalDate date : dateList) {
            DailyStatisticsDTO daily = byDate.get(date);
            orderCountList.add(daily == null || daily.getTotalOrders() == null ? 0 : daily.getTotalOrders());
            validOrderCountList.add(daily == null || daily.getValidOrders() == null ? 0 : daily.getValidOrders());
        }

        //计算时间区间内的订单总数量
        int totalOrderCount = orderCountList.stream().mapToInt(Integer::intValue).sum();

        //计算时间区间内的有效订单数量
        int validOrderCount = validOrderCountList.stream().mapToInt(Integer::intValue).sum();

        double orderCompletionRate = 0.0;
        if (totalOrderCount != 0) {
            //计算订单完成率
            orderCompletionRate = (double) validOrderCount / totalOrderCount;
        }

        return  OrderReportVO.builder()
                .dateList(StringUtils.join(dateList,","))
                .orderCountList(StringUtils.join(orderCountList,","))
                .validOrderCountList(StringUtils.join(validOrderCountList,","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    /**
     * 统计指定时间区间内的销量排名前10
     * @param begin
     * @param end
     * @return
     */
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        validateRange(begin, end);
        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(
                begin.atStartOfDay(), end.plusDays(1).atStartOfDay());

        List<String> names = salesTop10.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList());
        String nameList = StringUtils.join(names, ",");

        List<Integer> numbers = salesTop10.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList());
        String numberList = StringUtils.join(numbers, ",");

        //封装返回结果数据
        return SalesTop10ReportVO
                .builder()
                .nameList(nameList)
                .numberList(numberList)
                .build();
    }

    /**
     * 导出运营数据报表
     * @param response
     */
    public void exportBusinessData(HttpServletResponse response) {
        //1. 查询数据库，获取营业数据---查询最近30天的运营数据
        LocalDate dateBegin = LocalDate.now().minusDays(EXPORT_DAYS);
        LocalDate dateEnd = LocalDate.now().minusDays(1);

        // 半开区间：[dateBegin 00:00, dateEnd+1 00:00)
        BusinessDataVO businessDataVO = workspaceService.getBusinessData(
                dateBegin.atStartOfDay(), dateEnd.plusDays(1).atStartOfDay());

        Map<LocalDate, DailyStatisticsDTO> orderRows = dailyOrderStatistics(dateBegin, dateEnd);
        Map<LocalDate, Integer> userRows = dailyNewUsers(dateBegin, dateEnd);

        //2. 通过POI将数据写入到Excel文件中
        InputStream in = this.getClass().getClassLoader().getResourceAsStream(EXCEL_TEMPLATE);
        if (in == null) {
            throw new IllegalStateException("运营数据报表模板缺失：" + EXCEL_TEMPLATE);
        }

        // 先把数据写进内存里的模板，最后一次性输出：导出行数固定 30 行，
        // SXSSF 的流式写收益为零，真正的问题是每个单元格一次查询（已改成上面两次聚合查询）。
        try (InputStream template = in;
             XSSFWorkbook excel = new XSSFWorkbook(template)) {
            //获取表格文件的Sheet页
            XSSFSheet sheet = excel.getSheet("Sheet1");
            if (sheet == null) {
                throw new IllegalStateException("运营数据报表模板缺少 Sheet1");
            }

            //填充数据--时间
            cell(sheet, 1, 1).setCellValue("时间：" + dateBegin + "至" + dateEnd);

            cell(sheet, 3, 2).setCellValue(businessDataVO.getTurnover());
            cell(sheet, 3, 4).setCellValue(businessDataVO.getOrderCompletionRate());
            cell(sheet, 3, 6).setCellValue(businessDataVO.getNewUsers());

            cell(sheet, 4, 2).setCellValue(businessDataVO.getValidOrderCount());
            cell(sheet, 4, 4).setCellValue(businessDataVO.getUnitPrice());

            //填充明细数据
            for (int i = 0; i < EXPORT_DAYS; i++) {
                LocalDate date = dateBegin.plusDays(i);
                DailyStatisticsDTO daily = orderRows.get(date);
                int newUsers = userRows.getOrDefault(date, 0);

                double turnover = turnoverOf(daily);
                int validOrders = daily == null || daily.getValidOrders() == null ? 0 : daily.getValidOrders();
                int totalOrders = daily == null || daily.getTotalOrders() == null ? 0 : daily.getTotalOrders();

                cell(sheet, 7 + i, 1).setCellValue(date.toString());
                cell(sheet, 7 + i, 2).setCellValue(turnover);
                cell(sheet, 7 + i, 3).setCellValue(validOrders);
                cell(sheet, 7 + i, 4).setCellValue(totalOrders == 0 ? 0.0 : (double) validOrders / totalOrders);
                cell(sheet, 7 + i, 5).setCellValue(validOrders == 0 ? 0.0 : turnover / validOrders);
                cell(sheet, 7 + i, 6).setCellValue(newUsers);
            }

            //3. 通过输出流将Excel文件下载到客户端浏览器
            // 必须显式声明内容类型与下载文件名：不设置时浏览器会尝试原地渲染，
            // 文件名也退化成 URL 最后一段，导出的文件无法归档
            String filename = "operation-data-" + dateBegin + "_" + dateEnd + ".xlsx";
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", contentDisposition(filename));

            try (ServletOutputStream out = response.getOutputStream()) {
                excel.write(out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("运营数据报表导出失败", e);
        }
    }

    /**
     * RFC 5987 形式的下载头：中文/空格文件名直接拼进 filename= 是不合法的，
     * 部分浏览器会截断，这里同时给 ASCII 回退名和 filename*。
     */
    private static String contentDisposition(String filename) {
        String asciiFallback = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = UriUtils.encode(filename, StandardCharsets.UTF_8);
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }

    /** 一次查询取回区间内按天聚合的订单数据，日期为 key（只含有订单的日期） */
    private Map<LocalDate, DailyStatisticsDTO> dailyOrderStatistics(LocalDate begin, LocalDate end) {
        List<DailyStatisticsDTO> rows = orderMapper.dailyOrderStatistics(
                begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
        Map<LocalDate, DailyStatisticsDTO> byDate = new LinkedHashMap<>();
        for (DailyStatisticsDTO row : rows) {
            if (row.getDate() != null) {
                byDate.put(row.getDate(), row);
            }
        }
        return byDate;
    }

    /** 一次查询取回区间内按天聚合的新增用户数 */
    private Map<LocalDate, Integer> dailyNewUsers(LocalDate begin, LocalDate end) {
        List<DailyStatisticsDTO> rows = userMapper.dailyNewUsers(
                begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }
        return rows.stream()
                .filter(row -> row.getDate() != null)
                .collect(Collectors.toMap(DailyStatisticsDTO::getDate,
                        row -> row.getNewUsers() == null ? 0 : row.getNewUsers(),
                        Integer::sum));
    }

    private static double turnoverOf(DailyStatisticsDTO daily) {
        return daily == null || daily.getTurnover() == null ? 0.0 : daily.getTurnover();
    }

    /**
     * 展开 [begin, end] 为逐日列表。
     * <p>
     * 原实现是 while (!begin.equals(end)) begin = begin.plusDays(1)：
     * 只要 begin 晚于 end，循环条件永远不成立之外的方向，会一直加下去直到内存耗尽，
     * 一个非法日期参数就能把服务打挂。
     */
    private List<LocalDate> buildDateList(LocalDate begin, LocalDate end) {
        validateRange(begin, end);
        List<LocalDate> dateList = new ArrayList<>();
        for (LocalDate date = begin; !date.isAfter(end); date = date.plusDays(1)) {
            dateList.add(date);
        }
        return dateList;
    }

    /** 校验统计区间：空值、倒序、跨度过大都在这里挡住，而不是让 SQL 或循环去猜 */
    private void validateRange(LocalDate begin, LocalDate end) {
        if (begin == null || end == null) {
            throw new IllegalArgumentException("统计开始时间和结束时间不能为空");
        }
        if (begin.isAfter(end)) {
            throw new IllegalArgumentException("统计开始时间不能晚于结束时间：" + begin + " > " + end);
        }
        if (begin.plusDays(MAX_STATISTICS_DAYS - 1).isBefore(end)) {
            throw new IllegalArgumentException("统计区间最多 " + MAX_STATISTICS_DAYS + " 天");
        }
    }

    /** 按行列取单元格：模板缺行缺列时给出明确异常，而不是在链式调用里抛 NPE */
    private XSSFCell cell(XSSFSheet sheet, int rowIndex, int columnIndex) {
        XSSFRow row = sheet.getRow(rowIndex);
        if (row == null) {
            throw new IllegalStateException("报表模板缺少第 " + (rowIndex + 1) + " 行，请检查模板文件");
        }
        XSSFCell cell = row.getCell(columnIndex);
        if (cell == null) {
            throw new IllegalStateException("报表模板第 " + (rowIndex + 1) + " 行缺少第 "
                    + (columnIndex + 1) + " 列，请检查模板文件");
        }
        return cell;
    }
}
