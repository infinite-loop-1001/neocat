package com.neocat.query.domain.series;

/**
 * 数据质量标记（PRD 00 §6、技术方案 03 §4.2）。
 *
 * <p>核心语义：
 * <ul>
 *   <li>{@link #OK} 有数据；</li>
 *   <li>{@link #ZERO} 确认无调用（数据完整且次数为零）；</li>
 *   <li>{@link #NO_DATA} 缺数据：采集丢弃、分析失败、尚未完成或序列不存在 —— <b>不等于零</b>；</li>
 *   <li>{@link #DROPPED} 该桶存在队列满丢弃记录；</li>
 *   <li>{@link #MERGED_OTHER} Metric 具体序列该小时被并入 other；</li>
 *   <li>{@link #PARTIAL} 部分覆盖（滚动范围首尾桶）；</li>
 *   <li>{@link #REALTIME} 当前仍在写入的桶。</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("query")
public enum Quality {
    OK,
    ZERO,
    NO_DATA,
    DROPPED,
    MERGED_OTHER,
    PARTIAL,
    REALTIME;

    /** 该质量标记是否表示"有可比较的数值"。 */
    public boolean comparable() {
        return this == OK || this == ZERO || this == REALTIME || this == PARTIAL;
    }
    /** 该质量标记是否表示缺口（图表应断开而非画 0）。 */
    public boolean gap() {
        return this == NO_DATA || this == DROPPED || this == MERGED_OTHER;
    }
}
