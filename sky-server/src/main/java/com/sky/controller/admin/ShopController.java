package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.ShopStatusService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController("adminShopController")
@RequestMapping("/admin/shop")
@Api(tags = "店铺相关接口")
@Slf4j
public class ShopController {

    private final ShopStatusService shopStatusService;

    public ShopController(ShopStatusService shopStatusService) {
        this.shopStatusService = shopStatusService;
    }

    /**
     * 设置店铺的营业状态
     */
    @PutMapping("/{status}")
    @ApiOperation("设置店铺的营业状态")
    public Result setStatus(@PathVariable Integer status) {
        shopStatusService.setStatus(status);
        return Result.success();
    }

    /**
     * 获取店铺的营业状态
     */
    @GetMapping("/status")
    @ApiOperation("获取店铺的营业状态")
    public Result<Integer> getStatus() {
        return Result.success(shopStatusService.getStatus());
    }
}
