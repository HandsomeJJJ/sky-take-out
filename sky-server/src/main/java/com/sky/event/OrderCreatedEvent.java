package com.sky.event;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.Getter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单创建事件，只在事务提交后投递到消息中间件。 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OrderCreatedEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long orderId;
    private String orderNumber;
    private Long userId;
    private BigDecimal amount;
    private LocalDateTime orderTime;
}
