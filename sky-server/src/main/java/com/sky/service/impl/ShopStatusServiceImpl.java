package com.sky.service.impl;

import com.sky.constant.StatusConstant;
import com.sky.service.ShopStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ShopStatusServiceImpl implements ShopStatusService {

    /** 营业状态是全局单值，不需要按实例区分 */
    static final String REDIS_KEY = "sky:shop:status";

    private final RedisTemplate<String, Object> redisTemplate;

    public ShopStatusServiceImpl(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Integer getStatus() {
        Object raw = redisTemplate.opsForValue().get(REDIS_KEY);
        if (raw == null) {
            // /user/shop/status 免鉴权，缓存被清时返回 null 会让调用方拆箱 NPE
            log.warn("营业状态缓存缺失，按营业中返回");
            return StatusConstant.ENABLE;
        }
        return raw instanceof Number ? ((Number) raw).intValue() : Integer.valueOf(raw.toString());
    }

    public void setStatus(Integer status) {
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new IllegalArgumentException("营业状态只能是 0（打烊）或 1（营业）");
        }
        redisTemplate.opsForValue().set(REDIS_KEY, status);
        log.info("店铺营业状态已设置为：{}", StatusConstant.ENABLE.equals(status) ? "营业中" : "打烊中");
    }
}
