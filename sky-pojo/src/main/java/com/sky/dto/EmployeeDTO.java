package com.sky.dto;

import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.io.Serializable;

@Data
public class EmployeeDTO implements Serializable {

    private Long id;

    @NotBlank(message = "登录账号不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$", message = "登录账号只能是 3-20 位字母、数字或下划线")
    private String username;

    @NotBlank(message = "姓名不能为空")
    private String name;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @ToString.Exclude
    private String phone;

    @Pattern(regexp = "^[01]$", message = "性别只能是 0 或 1")
    private String sex;

    @Pattern(regexp = "^\\d{15}$|^\\d{17}[0-9Xx]$", message = "身份证号格式不正确")
    @ToString.Exclude
    private String idNumber;

}
