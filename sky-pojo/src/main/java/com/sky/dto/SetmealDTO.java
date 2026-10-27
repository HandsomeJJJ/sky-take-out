package com.sky.dto;

import com.sky.entity.SetmealDish;
import lombok.Data;
import javax.validation.constraints.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class SetmealDTO implements Serializable {

    private Long id;

    //分类id
    @NotNull(message = "套餐分类不能为空")
    private Long categoryId;

    //套餐名称
    @NotBlank(message = "套餐名称不能为空")
    @Size(max = 32, message = "套餐名称最多 32 字")
    private String name;

    //套餐价格
    @NotNull(message = "套餐价格不能为空")
    @DecimalMin(value = "0.01", message = "套餐价格必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "套餐价格最多两位小数")
    private BigDecimal price;

    //状态 0:停用 1:启用
    private Integer status;

    //描述信息
    private String description;

    //图片
    private String image;

    //套餐菜品关系
    private List<SetmealDish> setmealDishes = new ArrayList<>();

}
