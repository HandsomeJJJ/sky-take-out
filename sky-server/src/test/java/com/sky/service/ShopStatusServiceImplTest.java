package com.sky.service;

import com.sky.constant.StatusConstant;
import com.sky.mapper.ShopStatusMapper;
import com.sky.service.impl.ShopStatusServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 营业状态：以数据库为准，Redis 只是缓存；读不到不能默认"营业中"。
 */
@ExtendWith(MockitoExtension.class)
class ShopStatusServiceImplTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private ShopStatusMapper shopStatusMapper;

    private ShopStatusServiceImpl newService() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        return new ShopStatusServiceImpl(redisTemplate, shopStatusMapper);
    }

    @Test
    @DisplayName("缓存命中时不查库")
    void readsFromCacheWhenPresent() {
        ShopStatusServiceImpl service = newService();
        when(valueOperations.get("sky:shop:status")).thenReturn(0);

        assertThat(service.getStatus()).isEqualTo(StatusConstant.DISABLE);
        assertThat(service.isOpen()).isFalse();
        verify(shopStatusMapper, never()).getStatus();
    }

    @Test
    @DisplayName("缓存缺失时回源数据库并回填缓存")
    void fallsBackToDatabaseOnCacheMiss() {
        ShopStatusServiceImpl service = newService();
        when(valueOperations.get("sky:shop:status")).thenReturn(null);
        when(shopStatusMapper.getStatus()).thenReturn(StatusConstant.DISABLE);

        assertThat(service.isOpen()).isFalse();
        verify(shopStatusMapper).getStatus();
        verify(valueOperations).set(eq("sky:shop:status"), eq(StatusConstant.DISABLE), any(Duration.class));
    }

    @Test
    @DisplayName("库里也没有时初始化一行，而不是每次读都靠默认值猜")
    void seedsDatabaseRowWhenMissing() {
        ShopStatusServiceImpl service = newService();
        when(valueOperations.get("sky:shop:status")).thenReturn(null);
        when(shopStatusMapper.getStatus()).thenReturn(null);

        assertThat(service.getStatus()).isEqualTo(StatusConstant.ENABLE);
        verify(shopStatusMapper).upsert(eq(StatusConstant.ENABLE), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Redis 故障时降级读库，不把下单链路整个打挂")
    void degradesToDatabaseWhenRedisFails() {
        ShopStatusServiceImpl service = newService();
        when(valueOperations.get("sky:shop:status")).thenThrow(new IllegalStateException("redis down"));
        when(shopStatusMapper.getStatus()).thenReturn(StatusConstant.DISABLE);

        assertThat(service.isOpen()).isFalse();
    }

    @Test
    @DisplayName("设置状态先落库再刷缓存")
    void writesDatabaseBeforeCache() {
        ShopStatusServiceImpl service = newService();

        service.setStatus(StatusConstant.DISABLE);

        verify(shopStatusMapper).upsert(eq(StatusConstant.DISABLE), any(LocalDateTime.class));
        verify(valueOperations).set(eq("sky:shop:status"), eq(StatusConstant.DISABLE), any(Duration.class));
    }

    @Test
    @DisplayName("非法状态值直接拒绝，不能把 2 当成打烊存进去")
    void rejectsIllegalStatus() {
        ShopStatusServiceImpl service = newService();

        assertThatThrownBy(() -> service.setStatus(2)).isInstanceOf(IllegalArgumentException.class);
        verify(shopStatusMapper, never()).upsert(any(), any());
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }
}
