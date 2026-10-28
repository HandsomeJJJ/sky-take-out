package com.sky.controller.user;

import com.sky.entity.OrderNotification;
import com.sky.result.Result;
import com.sky.service.OrderNotificationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端订单通知。
 * <p>
 * 管理端接单/拒单/派送/完成过去对用户完全静默，只有支付和催单走了 WebSocket 广播，
 * 而 WebSocket 连接没有和用户绑定，做不了定向推送。状态变更现在统一落一行通知，
 * 由用户端拉取；接口都在 /user/** 下，登录态即归属，不接收 userId 参数。
 */
@RestController("userNotificationController")
@RequestMapping("/user/notification")
@Api(tags = "C端-订单通知")
@Slf4j
public class NotificationController {

    @Autowired
    private OrderNotificationService notificationService;

    @GetMapping("/list")
    @ApiOperation("订单通知列表")
    public Result<List<OrderNotification>> list(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(notificationService.listMine(limit));
    }

    @GetMapping("/unread-count")
    @ApiOperation("未读通知数量")
    public Result<Integer> unreadCount() {
        return Result.success(notificationService.unreadCount());
    }

    @PostMapping("/read")
    @ApiOperation("全部标记已读")
    public Result<String> markRead() {
        notificationService.markAllRead();
        return Result.success();
    }
}
