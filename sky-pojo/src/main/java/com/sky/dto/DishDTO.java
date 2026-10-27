package com.sky.dto;

import com.sky.entity.DishFlavor;
import lombok.Data;
import javax.validation.constraints.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class DishDTO implements Serializable {

    private Long id;
    //菜品名称
    @NotBlank(message = "菜品名称不能为空")
    @Size(max = 32, message = "菜品名称最多 32 字")
    private String name;
    //菜品分类id
    @NotNull(message = "菜品分类不能为空")
    private Long categoryId;
    //菜品价格
    @NotNull(message = "菜品价格不能为空")
    @DecimalMin(value = "0.01", message = "菜品价格必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "菜品价格最多两位小数")
    private BigDecimal price;
    //图片
    private String image;
    //描述信息
    private String description;
    //0 停售 1 起售
    @Min(value = 0, message = "菜品状态只能是 0 或 1")
    @Max(value = 1, message = "菜品状态只能是 0 或 1")
    private Integer status;
    //口味
    private List<DishFlavor> flavors = new ArrayList<>();

}
