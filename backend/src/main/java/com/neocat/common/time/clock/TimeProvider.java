package com.neocat.common.time.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.modulith.NamedInterface;

/**
 * 公共 UTC 墙上时间入口；业务时区由平台配置决定。
 *
 * <p>只暴露取时能力，不提供设置时钟的方法：Java 生产代码无法改写私有静态字段，
 * Groovy 单测直接给该字段赋值即可控制全局时间，不引入任何面向生产的开关。
 */
@NamedInterface("time")
public final class TimeProvider {
    private static volatile Clock clock = Clock.systemUTC();

    private TimeProvider() {
    }

    public static Instant now() {
        return clock.instant();
    }

    public static long millis() {
        return clock.millis();
    }

    /**
     * 当前时刻按延迟回退后所在分钟点的起点（UTC 分钟边界）。
     *
     * <p>延迟由调用方从领域配置读取后传入，TimeProvider 不感知业务配置。分钟点向下对齐，
     * 与判定使用的分钟边界一致；该点是否已完全落库取决于延迟取值。
     */
    public static Instant delayedMinuteStart(long delaySeconds) {
        return now().minusSeconds(delaySeconds).truncatedTo(ChronoUnit.MINUTES);
    }
}
