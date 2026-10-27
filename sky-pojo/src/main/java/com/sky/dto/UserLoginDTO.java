package com.sky.dto;

import lombok.Data;

import javax.validation.constraints.*;
import java.io.Serializable;

/**
 * C端用户登录
 */
@Data
public class UserLoginDTO implements Serializable {

    @NotBlank(message = "微信登录凭证不能为空")
    private String code;

}
