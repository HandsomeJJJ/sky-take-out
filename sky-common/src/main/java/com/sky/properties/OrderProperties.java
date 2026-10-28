package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 下单相关的业务参数。打包费这类"会算进用户实付金额"的规则只能由服务端持有，
 * 放在配置里是为了让口径可调，而不是回到"由客户端填"。
 */
@Component
@ConfigurationProperties(prefix = "sky.order")
@Data
public class OrderProperties {

    /** 每份商品的打包费（元） */
    private BigDecimal packFeePerItem = new BigDecimal("1.00");

    /** 单笔订单打包费上限（元），防止份数很多时打包费高得离谱 */
    private BigDecimal packFeeMax = new BigDecimal("20.00");

    /** 单个商品可购买的最大份数，与购物车行的上限保持一致 */
    private Integer maxNumberPerItem = 99;
}
