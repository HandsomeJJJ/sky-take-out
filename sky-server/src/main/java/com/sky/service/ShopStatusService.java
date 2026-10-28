package com.sky.service;

/**
 * 店铺营业状态：管理端设置、两端读取。
 */
public interface ShopStatusService {

    /**
     * 读取营业状态。缓存缺失时回源数据库，不允许"读不到就当作营业中"。
     */
    Integer getStatus();

    /** 是否营业中。打烊时用户端不能加购、不能下单。 */
    boolean isOpen();

    void setStatus(Integer status);
}
