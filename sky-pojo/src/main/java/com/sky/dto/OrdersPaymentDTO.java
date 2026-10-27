package com.sky.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.io.Serializable;

@Data
public class OrdersPaymentDTO implements Serializable {
    //订单号
    @NotBlank(message = "订单号不能为空")
    private String orderNumber;

    //付款方式
    @NotNull(message = "付款方式不能为空")
    @Min(value = 1, message = "付款方式只能是 1 或 2")
    @Max(value = 2, message = "付款方式只能是 1 或 2")
    private Integer payMethod;

}
