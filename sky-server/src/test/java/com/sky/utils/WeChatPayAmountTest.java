package com.sky.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 元转分是支付链路上唯一一处"算错就是资损"的换算，单独锁住。
 */
class WeChatPayAmountTest {

    @Test
    @DisplayName("常规金额按分取整")
    void convertsYuanToFen() {
        assertThat(WeChatPayUtil.toFen(new BigDecimal("0.01"))).isEqualTo(1);
        assertThat(WeChatPayUtil.toFen(new BigDecimal("50.00"))).isEqualTo(5000);
        assertThat(WeChatPayUtil.toFen(new BigDecimal("12.34"))).isEqualTo(1234);
    }

    @Test
    @DisplayName("半分金额四舍五入，不再被 intValue() 截断")
    void roundsHalfUpInsteadOfTruncating() {
        // 旧实现先 setScale(2) 再 intValue()，2 位小数被直接丢掉
        assertThat(WeChatPayUtil.toFen(new BigDecimal("2.005"))).isEqualTo(201);
        assertThat(WeChatPayUtil.toFen(new BigDecimal("2.004"))).isEqualTo(200);
    }

    @Test
    @DisplayName("0 元与负数不允许发起支付/退款")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> WeChatPayUtil.toFen(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeChatPayUtil.toFen(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeChatPayUtil.toFen(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
