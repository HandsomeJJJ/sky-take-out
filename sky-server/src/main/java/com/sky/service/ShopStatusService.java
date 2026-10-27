package com.sky.service;

/**
 * 店铺营业状态：管理端设置、两端读取。
 */
public interface ShopStatusService {

    /**
     * 读取营业状态。缓存缺失时返回营业中，不能返回 null 让调用方拆箱。
     */
    Integer getStatus();

    void setStatus(Integer status);
}
