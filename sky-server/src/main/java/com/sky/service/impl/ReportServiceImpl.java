package com.sky.service.impl;

import com.sky.service.ReportService;
import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
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

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * 统计指定时间区间内的营业额数据
     *
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = buildDateList(begin, end);

        //存放每天的营业额
        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            //查询date日期对应的营业额数据，营业额是指：状态为“已完成”的订单金额合计
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            // select sum(amount) from orders where order_time > beginTime and order_time < endTime and status = 5
            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover = orderMapper.sumByMap(map);
            turnover = turnover == null ? 0.0 : turnover;
            turnoverList.add(turnover);
        }

        //封装返回结果
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

        //存放每天的新增用户数量 select count(id) from user where create_time < ? and create_time > ?
        List<Integer> newUserList = new ArrayList<>();
        //存放每天的总用户数量 select count(id) from user where create_time < ?
        List<Integer> totalUserList = new ArrayList<>();

        for (LocalDate date : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            Map map = new HashMap();
            map.put("end", endTime);

            //总用户数量
            Integer totalUser = userMapper.countByMap(map);

            map.put("begin", beginTime);
            //新增用户数量
            Integer newUser = userMapper.countByMap(map);

            totalUserList.add(totalUser);
            newUserList.add(newUser);
        }

        //封装结果数据
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

        //存放每天的订单总数
        List<Integer> orderCountList = new ArrayList<>();
        //存放每天的有效订单数
        List<Integer> validOrderCountList = new ArrayList<>();

        //遍历dateList集合，查询每天的有效订单数和订单总数
        for (LocalDate date : dateList) {
            //查询每天的订单总数 select count(id) from orders where order_time > ? and order_time < ?
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);
            Integer orderCount = getOrderCount(beginTime, endTime, null);

            //查询每天的有效订单数 select count(id) from orders where order_time > ? and order_time < ? and status = 5
            Integer validOrderCount = getOrderCount(beginTime, endTime, Orders.COMPLETED);

            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderCount);
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

    /** 报表一次最多统计的天数，避免请求把几年数据拉进内存 */
    private static final int MAX_STATISTICS_DAYS = 366;

    /**
     * 展开 [begin, end] 为逐日列表。
     * <p>
     * 原实现是 while (!begin.equals(end)) begin = begin.plusDays(1)：
     * 只要 begin 晚于 end，循环条件永远不成立之外的方向，会一直加下去直到内存耗尽，
     * 一个非法日期参数就能把服务打挂。
     */
    private List<LocalDate> buildDateList(LocalDate begin, LocalDate end) {
        if (begin == null || end == null) {
            throw new IllegalArgumentException("统计开始时间和结束时间不能为空");
        }
        if (begin.isAfter(end)) {
            throw new IllegalArgumentException("统计开始时间不能晚于结束时间：" + begin + " > " + end);
        }
        if (begin.plusDays(MAX_STATISTICS_DAYS - 1).isBefore(end)) {
            throw new IllegalArgumentException("统计区间最多 " + MAX_STATISTICS_DAYS + " 天");
        }

        List<LocalDate> dateList = new ArrayList<>();
        for (LocalDate date = begin; !date.isAfter(end); date = date.plusDays(1)) {
            dateList.add(date);
        }
        return dateList;
    }

    /**
     * 根据条件统计订单数量
     * @param begin
     * @param end
     * @param status
     * @return
     */
    private Integer getOrderCount(LocalDateTime begin, LocalDateTime end, Integer status){
        Map map = new HashMap();
        map.put("begin",begin);
        map.put("end",end);
        map.put("status",status);

        return orderMapper.countByMap(map);
    }

    /**
     * 统计指定时间区间内的销量排名前10
     * @param begin
     * @param end
     * @return
     */
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);

        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(beginTime, endTime);
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

        //查询概览数据
        BusinessDataVO businessDataVO = workspaceService.getBusinessData(LocalDateTime.of(dateBegin, LocalTime.MIN), LocalDateTime.of(dateEnd, LocalTime.MAX));

        //2. 通过POI将数据写入到Excel文件中
        InputStream in = this.getClass().getClassLoader().getResourceAsStream(EXCEL_TEMPLATE);
        if (in == null) {
            throw new IllegalStateException("运营数据报表模板缺失：" + EXCEL_TEMPLATE);
        }

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
                //查询某一天的营业数据
                BusinessDataVO businessData = workspaceService.getBusinessData(
                        LocalDateTime.of(date, LocalTime.MIN), LocalDateTime.of(date, LocalTime.MAX));

                cell(sheet, 7 + i, 1).setCellValue(date.toString());
                cell(sheet, 7 + i, 2).setCellValue(businessData.getTurnover());
                cell(sheet, 7 + i, 3).setCellValue(businessData.getValidOrderCount());
                cell(sheet, 7 + i, 4).setCellValue(businessData.getOrderCompletionRate());
                cell(sheet, 7 + i, 5).setCellValue(businessData.getUnitPrice());
                cell(sheet, 7 + i, 6).setCellValue(businessData.getNewUsers());
            }

            //3. 通过输出流将Excel文件下载到客户端浏览器
            try (ServletOutputStream out = response.getOutputStream()) {
                excel.write(out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("运营数据报表导出失败", e);
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
