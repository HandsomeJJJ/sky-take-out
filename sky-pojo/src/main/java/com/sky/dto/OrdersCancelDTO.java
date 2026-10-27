package com.sky.dto;

import lombok.Data;

import javax.validation.constraints.*;
import java.io.Serializable;

@Data
public class OrdersCancelDTO implements Serializable {

    @NotNull(message = "订单id不能为空")
    private Long id;
    //订单取消原因
    @NotBlank(message = "取消原因不能为空")
    @Size(max = 100, message = "取消原因最多 100 字")
    private String cancelReason;

}
