package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储文件的保留策略。
 */
@Component
@ConfigurationProperties(prefix = "sky.media")
@Data
public class MediaProperties {

    /**
     * 不再被引用之后，还要在 bucket 里保留多少天才物理删除。
     * 默认 180 天：历史订单明细里存的是图片 URL，用户回看半年前的订单时图还得在。
     */
    private Integer retentionDays = 180;

    /** 单次回收任务最多处理多少个对象 */
    private Integer purgeBatchSize = 100;
}
