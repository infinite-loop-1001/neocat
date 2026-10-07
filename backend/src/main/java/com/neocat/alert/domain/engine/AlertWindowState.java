package com.neocat.alert.domain.engine;

import com.google.common.collect.Lists;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

import java.util.List;

/**
 * 规则窗口状态（PRD 06 §3.2、§5）。
 *
 * <p>窗口只记录**启用之后产生的完整分钟点**：
 * <ul>
 *   <li>启用前的历史点**不得**用于填充窗口；</li>
 *   <li>补收件人后从补充时刻重新建立窗口，不追溯旧异常；</li>
 *   <li>编辑保存后窗口清零。</li>
 * </ul>
 *
 * <p>实现为「基线时刻 + X 个点」而不是无限追加：
 * X 未满时不判定；满后每次新点到达即滑动判定。
 *
 * fixme: 找不到 param
 * @param ruleId      规则 ID
 * @param baselineAt  窗口基线（启用/编辑/补人时刻）；早于该时刻的点被忽略
 * @param points      已记录的完整分钟点（时间升序）
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
// fixme: 定位是聚合, 可以修改内部字段, 不需要像值对象那样 copy on write, 并且要有自己独立的 repository
public class AlertWindowState {

    private final long ruleId;

    private final long baselineAt;

    private final List<Long> points;

    public AlertWindowState(long ruleId, long baselineAt, List<Long> points) {
        this.ruleId = ruleId;
        this.baselineAt = baselineAt;
        this.points = points;
    }

    /** 窗口是否已收集到足够的点。 */
    public boolean filled(int windowPoints) {
        return points.size() >= windowPoints;
    }


    public static AlertWindowState empty(long ruleId, long baselineAt) {
        return new AlertWindowState(ruleId, baselineAt, Lists.newArrayList());
    }
}
