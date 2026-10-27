package com.sky.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class OrdersSubmitDTO implements Serializable {
    @NotNull(message = "收货地址不能为空")
    //地址簿id
    private Long addressBookId;
    @NotNull(message = "付款方式不能为空")
    @Min(value = 1, message = "付款方式只能是 1微信 2支付宝")
    @Max(value = 2, message = "付款方式只能是 1微信 2支付宝")
    //付款方式
    private Integer payMethod;
    @Size(max = 200, message = "备注最多 200 字")
    //备注
    private String remark;
    //预计送达时间
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime estimatedDeliveryTime;
    @NotNull(message = "配送状态不能为空")
    @Min(value = 0, message = "配送状态只能是 0选择时间 1立即送出")
    @Max(value = 1, message = "配送状态只能是 0选择时间 1立即送出")
    //配送状态  1立即送出  0选择具体时间
    private Integer deliveryStatus;
    @NotNull(message = "餐具数量不能为空")
    @Min(value = 0, message = "餐具数量不能为负")
    @Max(value = 999, message = "餐具数量超出上限")
    //餐具数量
    private Integer tablewareNumber;
    @NotNull(message = "餐具数量状态不能为空")
    @Min(value = 0, message = "餐具数量状态只能是 0具体数量 1按餐量提供")
    @Max(value = 1, message = "餐具数量状态只能是 0具体数量 1按餐量提供")
    //餐具数量状态  1按餐量提供  0选择具体数量
    private Integer tablewareStatus;
    @NotNull(message = "打包费不能为空")
    @Min(value = 0, message = "打包费不能为负")
    @Max(value = 100, message = "打包费超出上限")
    //打包费
    private Integer packAmount;
    // 订单金额没有入参字段：金额只能由服务端按购物车明细计算
}
