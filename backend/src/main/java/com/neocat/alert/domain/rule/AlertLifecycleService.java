package com.neocat.alert.domain.rule;

import com.neocat.alert.domain.engine.AlertWindowState;
import com.neocat.common.locking.MySqlLocked;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 规则生命周期用例（PRD 06 §3、§12）。
 *
 * <p>状态机：
 * <pre>
 * 保存后关闭 ──手动启用──► 已启用 ──手动关闭──► 已关闭
 *                            │
 *                      编辑保存（自动）
 *                            ▼
 *                         已关闭（窗口清零）
 * </pre>
 *
 * <p>三条不变式由本类统一保证，调用方无法绕过：
 * <ol>
 *   <li>{@link #enable} 设置 `stateSince = enabledAt` 并清空窗口状态 ——
 *       保证「只使用启用时刻之后产生的完整分钟点」（PRD 06 §3.2）；</li>
 *   <li>{@link #edit} **无论原状态如何**，结果恒为关闭且窗口清零
 *       —— 保证「编辑后自动关闭、需再次手动启用」（PRD 06 §3.3）；</li>
 *   <li>{@link #disable} 与 {@link #delete} 都清空窗口状态 ——
 *       避免残留点在新一轮启用时被误当作启用后的点使用。</li>
 * </ol>
 */
@Service
@NamedInterface("alert")
// fixme: 这个类的逻辑提到 application 里, 并且和 PreviewService 和 AlertEngine 的逻辑合并
public class AlertLifecycleService {

    private final AlertRuleRepository repository;

    public AlertLifecycleService(AlertRuleRepository repository) {
        this.repository = repository;
    }

    /** §3.2 手动启用：设置窗口基线为启用时刻，并清空任何残留窗口点。 */
    @MySqlLocked("metadata")
    public AlertRule enable(long ruleId, long enabledAt) {
        AlertRule rule = require(ruleId);
        // fixme: 单独拆一个监控窗口的 repository 不要和 AlertRuleRepository 混合, 另外这里直接更新时间就行, 不用先删后增
        repository.clearWindowState(ruleId);
        repository.saveWindowState(AlertWindowState.empty(ruleId, enabledAt));
        // fixme: 先启用, 再处理监控窗口
        return repository.save(rule.withEnabled(true, enabledAt));
    }

    /** §3.3 手动关闭：清空窗口状态。 */
    @MySqlLocked("metadata")
    public AlertRule disable(long ruleId) {
        AlertRule rule = require(ruleId);
        repository.clearWindowState(ruleId);
        // fixme: 先禁用, 再处理监控窗口
        return repository.save(rule.withEnabled(false, null));
    }
    /**
     * §3.3 编辑：保存后**一律关闭**并清空窗口状态。
     *
     * <p>不接受「保持原启用状态」的选项：PRD 明确要求编辑后自动关闭，
     * 用户必须再次手动启用；再次启用时窗口从零建立。
     */
    @MySqlLocked("metadata")
    public AlertRule edit(long ruleId, AlertRule draft) {
        AlertRule existing = require(ruleId);
        AlertRule merged = new AlertRule(
                existing.getId(),
                draft.getScope(),
                draft.getOrgId(),
                draft.getName(),
                draft.getDescription(),
                draft.getTarget(),
                draft.getCombinator(),
                draft.getWindowPoints(),
                draft.getConditions(),
                draft.getRecipients(),
                draft.getChannels(),
                false,                  // 编辑后恒为关闭
                existing.isInvalid(),
                null);                  // 窗口基线清零
        repository.clearWindowState(ruleId);
        // fixme: 先更新告警规则, 再重置更新告警窗口
        return repository.save(merged);
    }
    /** 删除规则并清空窗口状态。 */
    @MySqlLocked("metadata")
    public void delete(long ruleId) {
        require(ruleId);
        repository.delete(ruleId);
        repository.clearWindowState(ruleId);
    }

    // ── 内部 ─────────────────────────────────────────────────
    // fixme: 这里不需要有这个函数
    private AlertRule require(long ruleId) {
        return Optional.ofNullable(repository.findById(ruleId))
                .orElseThrow(() -> new NoSuchElementException("规则不存在：" + ruleId));
    }
}
