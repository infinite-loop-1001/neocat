package com.neocat.query.domain.series;
import org.springframework.modulith.NamedInterface;

/**
 * 环比类型（PRD 03 §6、PRD 00 §12）。
 *
 * <p>支持「1 天前同时段 / 7 天前同时段 / 30 天前同时段」。
 * 对比按平台时区**整日偏移**，因此月环比不是「上一个自然月」。
 */
@NamedInterface("query")
public enum MomKind {
    DAY(1),
    WEEK(7),
    MONTH(30);

    private final int daysOffset;

    MomKind(int daysOffset) {
        this.daysOffset = daysOffset;
    }
    /** 整日偏移天数。 */
    public int daysOffset() {
        return daysOffset;
    }
}
