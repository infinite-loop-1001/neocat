package com.neocat.dashboard.domain.formula;

import com.neocat.query.domain.stat.Stat;
import java.util.Objects;
import org.springframework.modulith.NamedInterface;

/**
 * 公式单位（PRD 05 §4，技术方案 02 §9.2）。
 *
 * <p>界面不列单位，但系统内部必须校验单位兼容：
 * <ul>
 *   <li>加减要求单位兼容；</li>
 *   <li>乘除推导结果单位；</li>
 *   <li>除数为 0 时该点不可计算；</li>
 *   <li>不符合校验的公式不能保存。</li>
 * </ul>
 */
@NamedInterface("dashboard")
public enum Unit {
    /** 次数。 */
    COUNT("count"),
    /** 耗时（毫秒）。 */
    DURATION("duration"),
    /** 无量纲比例（0–1）。 */
    RATE("rate"),
    /** 耗时/次数（同 DURATION，但参与除法推导时语义更明确）。 */
    RATIO("ratio"),
    /** 常数（无量纲）。 */
    NUMBER("number");

    private final String label;

    Unit(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
    /** 统计项对应的单位。 */
    public static Unit of(Stat stat) {
        return switch (stat.unit()) {
            case COUNT -> COUNT;
            case DURATION -> DURATION;
            case RATE -> RATE;
        };
    }
    /** 加减是否兼容：同单位可加。 */
    public boolean compatibleWith(Unit other) {
        return Objects.equals(this, other);
    }
    /** 乘法推导。 */
    public Unit multiply(Unit other) {
        if (Objects.equals(this, NUMBER)) {
            return other;
        }
        if (Objects.equals(other, NUMBER)) {
            return this;
        }
        if ((Objects.equals(this, DURATION) || Objects.equals(this, RATIO)) && Objects.equals(other, COUNT)) {
            return DURATION;         // 平均耗时 × 次数 = 耗时总和
        }
        if (Objects.equals(this, COUNT) && (Objects.equals(other, DURATION) || Objects.equals(other, RATIO))) {
            return DURATION;
        }
        if (Objects.equals(this, RATE) || Objects.equals(other, RATE)) {
            return RATE;
        }
        return COUNT;               // 次数 × 次数
    }
    /** 除法推导。 */
    public Unit divide(Unit other) {
        if (Objects.equals(other, NUMBER)) {
            return this;
        }
        if (Objects.equals(this, other)) {
            return RATE;             // 同单位相除得到比例
        }
        if ((Objects.equals(this, DURATION) || Objects.equals(this, RATIO)) && Objects.equals(other, COUNT)) {
            return RATIO;            // 耗时总和 ÷ 次数 = 平均耗时
        }
        if (Objects.equals(this, COUNT) && (Objects.equals(other, DURATION) || Objects.equals(other, RATIO))) {
            return RATIO;
        }
        return RATE;
    }
}
