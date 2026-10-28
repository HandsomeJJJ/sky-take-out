package com.sky.service.impl;

import com.sky.entity.MediaObject;
import com.sky.mapper.DishMapper;
import com.sky.mapper.MediaObjectMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.properties.MediaProperties;
import com.sky.service.MediaService;
import com.sky.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Service
@Slf4j
public class MediaServiceImpl implements MediaService {

    private final MediaObjectMapper mediaObjectMapper;
    private final DishMapper dishMapper;
    private final SetmealMapper setmealMapper;
    private final ShoppingCartMapper shoppingCartMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final AliOssUtil aliOssUtil;
    private final MediaProperties mediaProperties;

    public MediaServiceImpl(MediaObjectMapper mediaObjectMapper,
                            DishMapper dishMapper,
                            SetmealMapper setmealMapper,
                            ShoppingCartMapper shoppingCartMapper,
                            OrderDetailMapper orderDetailMapper,
                            AliOssUtil aliOssUtil,
                            MediaProperties mediaProperties) {
        this.mediaObjectMapper = mediaObjectMapper;
        this.dishMapper = dishMapper;
        this.setmealMapper = setmealMapper;
        this.shoppingCartMapper = shoppingCartMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.aliOssUtil = aliOssUtil;
        this.mediaProperties = mediaProperties;
    }

    public void register(String url) {
        String objectName = objectNameOf(url);
        if (objectName == null) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            mediaObjectMapper.register(objectName, url, now, now);
        } catch (Exception e) {
            // 对象已经上传成功，登记失败只是"以后少一个自动回收的线索"，不能让上传返回失败
            log.error("OSS 对象登记失败，url={}", url, e);
        }
    }

    public void retireIfUnreferenced(Collection<String> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        for (String url : urls) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            try {
                if (stillReferenced(url)) {
                    // 例如历史订单还在引用这张图：现在删掉，用户看自己的订单就是 404 图
                    mediaObjectMapper.reactivate(url, LocalDateTime.now());
                    log.info("图片仍被引用，暂不回收，url={}", url);
                    continue;
                }
                LocalDateTime now = LocalDateTime.now();
                int rows = mediaObjectMapper.markRetiring(url, now, now);
                if (rows == 1) {
                    log.info("图片已无引用，进入待回收，保留 {} 天后删除，url={}",
                            mediaProperties.getRetentionDays(), url);
                }
            } catch (Exception e) {
                log.error("标记图片待回收失败，url={}", url, e);
            }
        }
    }

    public int purgeExpired() {
        LocalDateTime dueBefore = LocalDateTime.now().minusDays(mediaProperties.getRetentionDays());
        List<MediaObject> due = mediaObjectMapper.findDueForDeletion(dueBefore,
                mediaProperties.getPurgeBatchSize());
        int deleted = 0;
        for (MediaObject object : due) {
            try {
                // 删除前再确认一次引用：待回收期间可能有商品又填了同一个 URL
                if (stillReferenced(object.getUrl())) {
                    mediaObjectMapper.reactivate(object.getUrl(), LocalDateTime.now());
                    continue;
                }
                // 条件更新领取删除权：多实例同时扫到同一行时只有一个会真的去调 OSS
                if (mediaObjectMapper.claimDeletion(object.getId(), LocalDateTime.now()) != 1) {
                    continue;
                }
                aliOssUtil.delete(object.getObjectName());
                deleted++;
            } catch (Exception e) {
                // 单个对象失败不能中断整批；状态已被领取为"已删除"的行如果实际没删成，
                // 只是留下一个孤儿对象（不会误删别人），下次人工核对时能捞出来
                log.error("OSS 对象删除失败，objectName={}", object.getObjectName(), e);
            }
        }
        return deleted;
    }

    /** 四张会存图片地址的表都查一遍，只要有一张还在用就不能回收 */
    private boolean stillReferenced(String url) {
        return dishMapper.countByImage(url) > 0
                || setmealMapper.countByImage(url) > 0
                || shoppingCartMapper.countByImage(url) > 0
                || orderDetailMapper.countByImage(url) > 0;
    }

    /**
     * 从访问地址反推 bucket 内的对象名：本项目上传后拼的是 https://bucket.endpoint/对象名，
     * 取最后一段即可；外部 CDN 域名带查询串的地址不登记，避免把一个猜出来的 key 删掉别人的东西。
     */
    private static String objectNameOf(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        if (url.contains("?")) {
            log.warn("带查询串的图片地址不做回收登记，url={}", url);
            return null;
        }
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) {
            return null;
        }
        return url.substring(slash + 1);
    }
}
