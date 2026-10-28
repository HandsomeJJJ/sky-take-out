package com.sky.service;

import java.util.Collection;

/**
 * 对象存储文件的生命周期：登记 -> 标记待回收 -> 到期物理删除。
 */
public interface MediaService {

    /** 上传成功后登记，登记失败不影响上传结果（对象已经在 bucket 里了） */
    void register(String url);

    /**
     * 业务表不再引用这些图片时调用：确认四张表（菜品、套餐、购物车、订单明细）都不再引用，
     * 才标记为待回收；只要还有引用就保持原状。
     */
    void retireIfUnreferenced(Collection<String> urls);

    /**
     * 回收到期对象：真正调用 OSS 删除。
     *
     * @return 本次删除的对象数
     */
    int purgeExpired();
}
