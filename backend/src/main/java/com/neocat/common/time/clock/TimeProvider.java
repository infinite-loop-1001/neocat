package com.neocat.common.time.clock;

import com.google.common.collect.Lists;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
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

    public static Long millis() {
        return clock.millis();
    }

    /**
     * 当前时刻按延迟回退后所在分钟点的起点（UTC 分钟边界）。
     *
     * <p>延迟由调用方从领域配置读取后传入，TimeProvider 不感知业务配置。分钟点向下对齐，
     * 与判定使用的分钟边界一致；该点是否已完全落库取决于延迟取值。
     */
    public static Instant delayedMinuteStart(Long delaySeconds) {
        if (delaySeconds < 0) {
            throw new IllegalArgumentException("delaySeconds cannot be negative");
        }
        return now().minusSeconds(delaySeconds).truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * 以给定分钟点为终点、向前倒推的连续分钟点窗口，含终点且最新点在前。
     *
     * <p>返回 {@code [minute, minute-1, ..., minute-(windowSize-1)]}，长度恰为 {@code windowSize}。
     * 传入点先向下对齐到分钟边界，与 {@link #delayedMinuteStart(Long)} 的边界一致；
     * 调用方需要 epoch 毫秒时自行调用 {@code toEpochMilli()}。
     *
     * @param minute     窗口终点分钟点；未对齐到分钟时先向下对齐
     * @param windowSize 窗口包含的连续分钟点个数，必须为正
     */
    public static List<Instant> minuteWindow(Instant minute, Integer windowSize) {
        Objects.requireNonNull(minute, "minute is required");
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be positive");
        }
        Instant end = minute.truncatedTo(ChronoUnit.MINUTES);
        List<Instant> window = Lists.newArrayList();
        for (int i = 0; i < windowSize; i++) {
            window.add(end.minus(i, ChronoUnit.MINUTES));
        }
        return window;
    }

    /**
     * {@link #minuteWindow(Instant, Integer)} 的 epoch 毫秒入口，供内部以 {@code Long minute} 传参的调用方使用。
     *
     * <p>先把毫秒还原为 UTC 分钟点再套用同一窗口语义；返回的仍是分钟点 {@code Instant}，
     * 需要毫秒时对结果调用 {@code toEpochMilli()}，不在本方法内固化为 {@code List<Long>}。
     *
     * @param minuteEpochMillis 窗口终点分钟点的 epoch 毫秒
     * @param windowSize        窗口包含的连续分钟点个数，必须为正
     */
    public static List<Instant> minuteWindow(Long minuteEpochMillis, Integer windowSize) {
        return minuteWindow(Instant.ofEpochMilli(minuteEpochMillis), windowSize);
    }
}
