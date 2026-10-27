package com.sky.dto;

import lombok.Data;

import javax.validation.constraints.*;
import java.io.Serializable;

@Data
public class OrdersRejectionDTO implements Serializable {

    @NotNull(message = "订单id不能为空")
    private Long id;

    //订单拒绝原因
    @NotBlank(message = "拒单原因不能为空")
    @Size(max = 100, message = "拒单原因最多 100 字")
    private String rejectionReason;

}
