package com.sky.service.impl;

import com.sky.constant.StatusConstant;
import com.sky.mapper.ShopStatusMapper;
import com.sky.service.ShopStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 营业状态以数据库为准，Redis 只是热路径缓存。
 * <p>
 * 原来只写 Redis：状态是"是否允许成交"这种要拦住钱的开关，
 * 缓存被清/换库/运维误删之后读不到，就只能靠代码里的默认值猜，
 * 猜成"营业中"就是打烊时间照样收单。现在读不到就回源 DB，DB 也没有就写入初始值，
 * 保证后续每一次读的都是一个真实落库的值。
 */
@Service
@Slf4j
public class ShopStatusServiceImpl implements ShopStatusService {

    /** 营业状态是全局单值，不需要按实例区分 */
    static final String REDIS_KEY = "sky:shop:status";

    /** 缓存只为了减少读库，给一个短的 TTL；失效后回源数据库而不是回源默认值 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ShopStatusMapper shopStatusMapper;

    public ShopStatusServiceImpl(RedisTemplate<String, Object> redisTemplate,
                                 ShopStatusMapper shopStatusMapper) {
        this.redisTemplate = redisTemplate;
        this.shopStatusMapper = shopStatusMapper;
    }

    public Integer getStatus() {
        Integer cached = readCache();
        if (cached != null) {
            return cached;
        }

        Integer status = shopStatusMapper.getStatus();
        if (status == null) {
            // 库里还没有这一行（首次接入 Flyway 之外的老库），用营业中初始化并落库，之后不再靠猜
            status = StatusConstant.ENABLE;
            shopStatusMapper.upsert(status, LocalDateTime.now());
            log.info("营业状态记录缺失，已初始化为营业中");
        }
        writeCache(status);
        return status;
    }

    public boolean isOpen() {
        return StatusConstant.ENABLE.equals(getStatus());
    }

    public void setStatus(Integer status) {
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new IllegalArgumentException("营业状态只能是 0（打烊）或 1（营业）");
        }
        // 先落库再刷缓存：写库失败时状态不会"看起来已经改了"
        shopStatusMapper.upsert(status, LocalDateTime.now());
        writeCache(status);
        log.info("店铺营业状态已设置为：{}", StatusConstant.ENABLE.equals(status) ? "营业中" : "打烊中");
    }

    /**
     * 缓存只是加速，Redis 抖动时必须退化成读库，不能让整个下单链路的读状态直接抛错。
     */
    private Integer readCache() {
        try {
            Object raw = redisTemplate.opsForValue().get(REDIS_KEY);
            if (raw == null) {
                return null;
            }
            return raw instanceof Number ? ((Number) raw).intValue() : Integer.valueOf(raw.toString());
        } catch (Exception e) {
            log.error("读取营业状态缓存失败，回源数据库", e);
            return null;
        }
    }

    private void writeCache(Integer status) {
        try {
            redisTemplate.opsForValue().set(REDIS_KEY, status, CACHE_TTL);
        } catch (Exception e) {
            log.error("写入营业状态缓存失败，已落库不影响正确性", e);
        }
    }
}
