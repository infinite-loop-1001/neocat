package com.neocat.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 统计数值的统一十进制策略：中间加减乘精确，除法七位，最终结果六位。
 */
public final class DecimalMath {

    public static final int DIVISION_SCALE = 7;

    public static final int RESULT_SCALE = 6;

    private DecimalMath() {
    }

    public static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, DIVISION_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal divide(long numerator, long denominator) {
        return divide(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator));
    }

    /**
     * 只在完整计算的结果边界调用；缺数仍为 null。
     */
    public static BigDecimal result(BigDecimal value) {
        return Objects.isNull(value) ? null : value.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }
}
