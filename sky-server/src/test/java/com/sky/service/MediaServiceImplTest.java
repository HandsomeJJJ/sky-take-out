package com.sky.service;

import com.sky.entity.MediaObject;
import com.sky.exception.BaseException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.MediaObjectMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.properties.MediaProperties;
import com.sky.service.impl.MediaServiceImpl;
import com.sky.utils.AliOssUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片回收的引用计数口径。
 * <p>
 * 关键约束：删菜品/换图时**不能**直接删 OSS 对象 —— 历史订单明细里存的就是这个 URL，
 * 删了用户回看订单就是 404 图。所以只有四张表都不再引用时才进入待回收，且还要再等保留期。
 */
@ExtendWith(MockitoExtension.class)
class MediaServiceImplTest {

    private static final String URL = "https://bucket.oss-cn-beijing.aliyuncs.com/abc123.png";

    @Mock
    private MediaObjectMapper mediaObjectMapper;
    @Mock
    private DishMapper dishMapper;
    @Mock
    private SetmealMapper setmealMapper;
    @Mock
    private ShoppingCartMapper shoppingCartMapper;
    @Mock
    private OrderDetailMapper orderDetailMapper;
    @Mock
    private AliOssUtil aliOssUtil;

    private MediaProperties mediaProperties;
    private MediaServiceImpl mediaService;

    @BeforeEach
    void setUp() {
        mediaProperties = new MediaProperties();
        mediaProperties.setRetentionDays(180);
        mediaProperties.setPurgeBatchSize(100);
        mediaService = new MediaServiceImpl(mediaObjectMapper, dishMapper, setmealMapper,
                shoppingCartMapper, orderDetailMapper, aliOssUtil, mediaProperties);
    }

    @Test
    @DisplayName("上传成功后按 URL 最后一段登记对象名")
    void registersObjectNameFromUrl() {
        mediaService.register(URL);

        verify(mediaObjectMapper).register(eq("abc123.png"), eq(URL), any(), any());
    }

    @Test
    @DisplayName("带查询串的地址不登记：反推出来的 objectName 不可信，删错就是删别人的文件")
    void refusesToRegisterUrlWithQuery() {
        mediaService.register("https://cdn.example.com/a.png?sig=xxx");

        verify(mediaObjectMapper, never()).register(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("登记失败不能让上传接口失败：对象已经在 bucket 里了")
    void keepsUploadSuccessfulWhenRegisterFails() {
        when(mediaObjectMapper.register(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        mediaService.register(URL);
    }

    @Test
    @DisplayName("四张表都不再引用时才标记待回收")
    void marksRetiringOnlyWhenNothingReferences() {
        when(dishMapper.countByImage(URL)).thenReturn(0);
        when(setmealMapper.countByImage(URL)).thenReturn(0);
        when(shoppingCartMapper.countByImage(URL)).thenReturn(0);
        when(orderDetailMapper.countByImage(URL)).thenReturn(0);
        when(mediaObjectMapper.markRetiring(eq(URL), any(), any())).thenReturn(1);

        mediaService.retireIfUnreferenced(List.of(URL));

        verify(mediaObjectMapper).markRetiring(eq(URL), any(), any());
    }

    @Test
    @DisplayName("历史订单还在引用这张图：不回收，并把之前误标的记录拉回在用")
    void keepsImageStillReferencedByOrderDetail() {
        when(dishMapper.countByImage(URL)).thenReturn(0);
        when(setmealMapper.countByImage(URL)).thenReturn(0);
        when(shoppingCartMapper.countByImage(URL)).thenReturn(0);
        when(orderDetailMapper.countByImage(URL)).thenReturn(2);

        mediaService.retireIfUnreferenced(List.of(URL));

        verify(mediaObjectMapper, never()).markRetiring(anyString(), any(), any());
        verify(mediaObjectMapper).reactivate(eq(URL), any());
    }

    @Test
    @DisplayName("到期对象：抢到删除权才真的调 OSS，抢不到就跳过")
    void claimsBeforeDeleting() {
        MediaObject mine = object(1L);
        MediaObject takenByOther = object(2L);
        when(mediaObjectMapper.findDueForDeletion(any(), eq(100)))
                .thenReturn(List.of(mine, takenByOther));
        when(mediaObjectMapper.claimDeletion(eq(1L), any())).thenReturn(1);
        when(mediaObjectMapper.claimDeletion(eq(2L), any())).thenReturn(0);

        int deleted = mediaService.purgeExpired();

        assertThat(deleted).isEqualTo(1);
        verify(aliOssUtil).delete("abc123.png");
    }

    @Test
    @DisplayName("删除前再确认一次引用：待回收期间可能有别的商品又填了同一个 URL")
    void rechecksReferenceBeforeDeleting() {
        when(mediaObjectMapper.findDueForDeletion(any(), anyInt()))
                .thenReturn(List.of(object(1L)));
        when(dishMapper.countByImage(URL)).thenReturn(1);

        int deleted = mediaService.purgeExpired();

        assertThat(deleted).isZero();
        verify(mediaObjectMapper, never()).claimDeletion(any(), any());
        verify(aliOssUtil, never()).delete(any());
        verify(mediaObjectMapper).reactivate(eq(URL), any());
    }

    @Test
    @DisplayName("到期时间按保留天数往前推，删除失败不会中断整批")
    void continuesAfterOssFailure() {
        MediaProperties properties = new MediaProperties();
        properties.setRetentionDays(30);
        properties.setPurgeBatchSize(5);
        MediaServiceImpl service = new MediaServiceImpl(mediaObjectMapper, dishMapper, setmealMapper,
                shoppingCartMapper, orderDetailMapper, aliOssUtil, properties);
        when(mediaObjectMapper.findDueForDeletion(any(), eq(5))).thenReturn(List.of(object(1L), object(2L)));
        when(mediaObjectMapper.claimDeletion(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new BaseException("OSS 删除失败"))
                .when(aliOssUtil).delete(anyString());

        ArgumentCaptor<LocalDateTime> dueBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        int deleted = service.purgeExpired();

        assertThat(deleted).isZero();
        verify(mediaObjectMapper).findDueForDeletion(dueBefore.capture(), eq(5));
        // 保留期 30 天：到期线应该落在今天往前 30 天附近，而不是 180 天
        assertThat(dueBefore.getValue()).isBefore(LocalDateTime.now().minusDays(29));
        verify(mediaObjectMapper).claimDeletion(eq(1L), any());
        verify(mediaObjectMapper).claimDeletion(eq(2L), any());
    }

    private static MediaObject object(Long id) {
        return MediaObject.builder()
                .id(id)
                .objectName("abc123.png")
                .url(URL)
                .status(MediaObject.RETIRING)
                .retireTime(LocalDateTime.now().minusDays(200))
                .build();
    }
}
