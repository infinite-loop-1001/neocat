package com.neocat.query.domain.report;

import java.util.List;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 机器维度视图（PRD 03 §7.3、§10）。
 *
 * <p>规则：
 * <ul>
 *   <li>默认按当前统计项贡献值从高到低显示 Top N；</li>
 *   <li>Top N 之外的机器合并为 {@code other}，用于数量类/速率类与总量**对账**；</li>
 *   <li>全部机器在可搜索、可排序、可分页的明细表中展示；</li>
 *   <li>用户可以勾选机器进行对比；</li>
 *   <li>**手动选机器后展示选中机器，不再自动加入 other**；</li>
 *   <li>Heartbeat 的 Top N 之外**不合并为 other**（PRD 03 §10）。</li>
 * </ul>
 *
 * @param top        Top N 机器行
 * @param other      其余机器合并行；Heartbeat 场景为 null
 * @param all        全部机器的明细行（未截断，供分页）
 * @param selected   用户手动勾选的机器行；非空时 top 与 other 不再使用
 */
@NamedInterface("query")
@Getter
@EqualsAndHashCode
@ToString
public class MachineView {
    private final List<MachineRow> top;

    private final MachineRow other;

    private final List<MachineRow> all;

    private final List<MachineRow> selected;

    public MachineView(List<MachineRow> top, MachineRow other, List<MachineRow> all, List<MachineRow> selected) {
        this.top = top;
        this.other = other;
        this.all = all;
        this.selected = selected;
    }

    public boolean hasSelection() {
        return Objects.nonNull(selected) && CollectionUtils.isNotEmpty(selected);
    }
    /** 数量类对账：Top N + other 应等于全量。 */
    public long reconciledTotal() {
        long sum = top.stream().mapToLong(MachineRow::getTotal).sum();
        if (Objects.nonNull(other)) {
            sum += other.getTotal();
        }
        return sum;
    }
    public long allTotal() {
        return all.stream().mapToLong(MachineRow::getTotal).sum();
    }
}
