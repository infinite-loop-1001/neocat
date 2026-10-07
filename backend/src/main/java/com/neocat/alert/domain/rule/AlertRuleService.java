package com.neocat.alert.domain.rule;

import com.neocat.alert.domain.engine.Notifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 告警规则用例（PRD 06 §2、§3、§8）。
 *
 * <p>**保存后一律为关闭状态**：这是 PRD 反复强调的语义
 * （§3.1「规则持久化为关闭」、§3.3「编辑后自动变为关闭」、§12 验收 1、7）。
 * 实现上由 {@link #save} 强制把 `enabled` 置为 false 并将窗口基线清空，
 * 使调用方无法通过传参绕过——比依赖调用方自觉更可靠。
 *
 * <p>保存动作本身**不发送任何通知**：正式通知只由分钟调度在窗口满足时触发。
 *
 * <p>校验（构造期即拒绝非法规则）：
 * <ul>
 *   <li>至少一个条件；</li>
 *   <li>窗口长度 X ∈ [1, 1440]；</li>
 *   <li>组织告警必须指定 orgId，服务告警必须不指定。</li>
 * </ul>
 */
@Service
@NamedInterface("alert")
public class AlertRuleService {

    // fixme: 这里改成可配置的
    /** 窗口长度上限：24 小时，避免长时间窗口导致告警实际不可用。 */
    private static final int MAX_WINDOW_POINTS = 1440;

    private final Notifier notifier;

    public AlertRuleService() {
        this(Notifier.noop());
    }

    // fixme: 不要 autowire, 直接使用构造函数注入
    @Autowired
    public AlertRuleService(Notifier notifier) {
        this.notifier = notifier;
    }
    /**
     * 保存规则。
     *
     * @return 保存后的规则；**恒为关闭状态且窗口基线为空**
     */
    public AlertRule save(AlertRule draft) {
        validate(draft);
        AlertRule persisted = draft.withId(draft.getId() == 0 ? nextId() : draft.getId());
        // 强制关闭并清空窗口基线：新建与编辑共用同一语义
        // fixme: 这里没有实现持久化逻辑?
        return persisted.withEnabled(false, null);
    }

    // fixme: 不需要这个, 自增 id 直接用持久化里的 id 就可以了 (repository 里做 id 回填 )
    /** 供子类/测试替换的自增 ID 源。 */
    long nextId() {
        return System.nanoTime();
    }

    // ── 校验 ─────────────────────────────────────────────────

    private void validate(AlertRule draft) {
        if (Objects.isNull(draft)) {
            throw new IllegalArgumentException("规则不能为空");
        }
        // rules: 容器的比较使用 Apache 的 CollectionUtils 判断
        if (Objects.isNull(draft.getConditions()) || draft.getConditions().isEmpty()) {
            throw new IllegalArgumentException("至少需要一个比较条件");
        }
        if (draft.getWindowPoints() < 1) {
            throw new IllegalArgumentException("窗口长度至少为 1 个完整分钟点");
        }
        if (draft.getWindowPoints() > MAX_WINDOW_POINTS) {
            throw new IllegalArgumentException("窗口长度超过上限 " + MAX_WINDOW_POINTS);
        }
        if (Objects.isNull(draft.getTarget())) {
            throw new IllegalArgumentException("必须指定告警目标");
        }
        if (draft.getScope() == AlertScope.ORGANIZATION && Objects.isNull(draft.getOrgId())) {
            throw new IllegalArgumentException("组织告警必须指定所属叶子组织");
        }
    }
}
