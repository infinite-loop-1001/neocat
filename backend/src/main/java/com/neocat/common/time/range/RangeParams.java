package com.neocat.common.time.range;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;

/**
 * `range` 查询参数解析（技术方案 03-api-contract.md §4.1）。
 *
 * <p>契约里 `range` 有两种写法，二者都必须支持：
 * <ul>
 *   <li><b>快捷范围</b>：{@code RECENT_1H}、{@code RECENT_24H}、{@code TODAY}、{@code THIS_WEEK}；</li>
 *   <li><b>固定周期</b>：{@code HOUR:<epoch millis>}、{@code DAY:<yyyy-MM-dd>}、
 *       {@code WEEK:<yyyy-MM-dd>}、{@code MONTH:<yyyy-MM>}。</li>
 * </ul>
 *
 * <p>固定周期是「前后翻页」这类窗口导航的载体：调用方给出对齐后的窗口起点，
 * 服务端据此返回该自然周期，而不是永远返回最近一小时。
 *
 * <p>无法识别或取值非法时一律回退到 {@code RECENT_1H}，不向调用方抛 500——
 * 时间参数写错不应该让整个报表页报错。
 */
@org.springframework.modulith.NamedInterface("time")
public class RangeParams {

    private RangeParams() {
    }
    /**
     * @param raw 原始参数，可为 null
     * @param now 计算快捷范围用的当前时刻
     * @return 解析结果；无法识别时返回 {@code RECENT_1H}
     */
    public static RangeSpec parse(String raw, Instant now) {
        String value = raw == null ? "" : raw.trim();

        int colon = value.indexOf(':');
        if (colon > 0) {
            String head = value.substring(0, colon).toUpperCase(Locale.ROOT);
            String tail = value.substring(colon + 1);
            try {
                switch (head) {
                    case "HOUR":
                        return new RangeSpec.Hour(Instant.ofEpochMilli(Long.parseLong(tail)));
                    case "DAY":
                        return new RangeSpec.Day(LocalDate.parse(tail));
                    case "WEEK":
                        return new RangeSpec.Week(LocalDate.parse(tail));
                    case "MONTH":
                        return new RangeSpec.Month(YearMonth.parse(tail));
                    default:
                        break;
                }
            } catch (RuntimeException ignored) {
                // 取值格式不合法：按未知范围处理
                return quick(value, now);
            }
        }

        return quick(value, now);
    }
    private static RangeSpec quick(String value, Instant now) {
        RangeQuick target = switch (value.toUpperCase(Locale.ROOT)) {
            case "RECENT_3H" -> RangeQuick.RECENT_3H;
            case "RECENT_6H" -> RangeQuick.RECENT_6H;
            case "RECENT_12H" -> RangeQuick.RECENT_12H;
            case "RECENT_24H" -> RangeQuick.RECENT_24H;
            case "TODAY" -> RangeQuick.TODAY;
            case "THIS_WEEK" -> RangeQuick.THIS_WEEK;
            default -> RangeQuick.RECENT_1H;
        };
        return new RangeSpec.QuickRange(target, now);
    }
}
