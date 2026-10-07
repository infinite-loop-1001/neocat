package com.neocat.alert.domain.recipient;

import com.neocat.alert.domain.engine.AlertWindowState;
import com.neocat.alert.domain.rule.AlertRule;
import com.neocat.alert.domain.rule.AlertRuleRepository;
import com.neocat.alert.domain.rule.AlertRuleService;
import com.neocat.common.locking.MySqlLocked;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 收件人维护（PRD 06 §9）。
 *
 * <p>事件处理规则：
 * <table border="1">
 *   <caption>事件 → 收件人变化</caption>
 *   <tr><th>事件</th><th>动作</th></tr>
 *   <tr><td>账号禁用</td><td>从所有规则移除该账号；<b>规则不关闭</b></td></tr>
 *   <tr><td>账号启用</td><td><b>不做任何事</b>（不恢复收件关系）</td></tr>
 *   <tr><td>失去组织资格</td><td>从该组织的组织告警中移除；服务告警不受影响</td></tr>
 *   <tr><td>获得组织资格</td><td><b>不自动加入</b>（只做移除，不做自动加入）</td></tr>
 *   <tr><td>组织删除</td><td>该叶子规则置为失效但<b>保留配置</b></td></tr>
 * </table>
 *
 * <p>「只做移除，不做自动加入」是有意为之：自动加入会让用户在不知情的情况下
 * 开始接收告警，而 PRD 06 §9 只要求移除，且 PRD 01 §3.4 明确「启用后不恢复收件人」。
 */
@Service
@NamedInterface("alert")
/**
 * fixme: 这类逻辑收束到 AlterRuleService 或者 handler 里,
 *  这里本质是 AlterRuleService 的领域逻辑 (修改告警规则中的通知人), 此后把这个类干掉
 * @see AlertRuleService
 */
public class RecipientService {

    private final AlertRuleRepository repository;

    private final RecipientGateway gateway;

    public RecipientService(AlertRuleRepository repository, RecipientGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    // fixme: 这部分逻辑需要写到 EventHandler 里面, 并且根据不同的内部领域事件类型分开处理, 不要塞到一个方法里面
    // rules: 处理内部领域事件涉及到领域内部逻辑时需要调用 DomainService, handler 只做逻辑的编排
    /** 处理账号/组织事件，返回被修改的规则。 */
    public List<AlertRule> onEvent(RecipientEvent event) {
        if (event == null) {
            return List.of();
        }
        if (event instanceof RecipientEvent.UserDisabled disabled) {
            return removeFromAll(disabled.getAccountId());
        }
        if (event instanceof RecipientEvent.UserEnabled) {
            // 不恢复任何收件关系
            return List.of();
        }
        if (event instanceof RecipientEvent.OrgMembershipChanged changed) {
            return changed.isGranted() ? List.of() : removeFromOrg(changed.getOrgId(), changed.getAccountId());
        }
        if (event instanceof RecipientEvent.OrgDeleted deleted) {
            return invalidateOrg(deleted.getOrgId());
        }
        return List.of();
    }

    /**
     * 计算某规则当前的有效收件人。
     *
     * <p>剔除禁用账号；组织告警还要求收件人仍是该叶子的有效成员。
     * 规则本身不因收件人清空而关闭。
     */
    public List<Long> effectiveRecipients(AlertRule rule) {
        if (rule == null || rule.getRecipients() == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Long accountId : rule.getRecipients()) {
            if (!gateway.isEnabled(accountId)) {
                continue;
            }
            if (rule.isOrganization() && rule.getOrgId() != null
                    && !gateway.isEffectiveMember(accountId, rule.getOrgId())) {
                continue;
            }
            result.add(accountId);
        }
        return List.copyOf(result);
    }

    /**
     * 补充收件人：**重建窗口基线**，不追溯旧异常（PRD 06 §9）。
     */
    @MySqlLocked("metadata")
    public AlertRule updateRecipients(long ruleId, List<Long> recipients, long addedAt) {
        AlertRule rule = Optional.ofNullable(repository.findById(ruleId))
                .orElseThrow(() -> new java.util.NoSuchElementException("规则不存在：" + ruleId));
        // rules: stream 流操作前需要进行判空, 类似 CollectionUtils.emptyIfNull().stream....
        List<Long> sanitized = distinct(recipients);

        AlertRule updated = rule.withRecipients(sanitized);
        AlertRule saved = repository.save(updated);

        // 窗口重建：新基线为补充时刻，点集为空
        // fixme: 先修改 AlterRule 再和告警窗口通过领域事件交互
        repository.clearWindowState(ruleId);
        repository.saveWindowState(AlertWindowState.empty(ruleId, addedAt));
        return saved;
    }
    /** 校验收件人选择合法：账号必须启用；组织告警还必须是该叶子的有效成员。 */
    public void validateSelection(AlertRule rule, List<Long> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        // rules: 遍历容器的时候不要单独 for 循环做可能高耗时的业务逻辑 (如 rpc, 数据库操作), 尽量使用批量处理
        for (Long accountId : recipients) {
            if (!gateway.isEnabled(accountId)) {
                throw new IllegalArgumentException("收件人必须是启用状态的账号：" + accountId);
            }
            if (rule.isOrganization() && rule.getOrgId() != null
                    && !gateway.isEffectiveMember(accountId, rule.getOrgId())) {
                throw new IllegalArgumentException("组织告警的收件人必须是该叶子的有效成员：" + accountId);
            }
        }
    }

    // ── 内部 ─────────────────────────────────────────────────

    private List<AlertRule> removeFromAll(long accountId) {
        List<AlertRule> changed = new ArrayList<>();
        for (AlertRule rule : repository.findAll()) {
            if (rule.getRecipients() == null || !rule.getRecipients().contains(accountId)) {
                continue;
            }
            List<Long> remaining = rule.getRecipients().stream().filter(id -> id != accountId).toList();
            changed.add(repository.save(rule.withRecipients(remaining)));
        }
        return changed;
    }

    private List<AlertRule> removeFromOrg(long orgId, long accountId) {
        List<AlertRule> changed = new ArrayList<>();
        for (AlertRule rule : repository.byOrg(orgId)) {
            // fixme: 抽一个公共方法在当前类里
            if (rule.getRecipients() == null || !rule.getRecipients().contains(accountId)) {
                continue;
            }
            List<Long> remaining = rule.getRecipients().stream().filter(id -> id != accountId).toList();
            changed.add(repository.save(rule.withRecipients(remaining)));
        }
        return changed;
    }
    /** 组织删除：该叶子组织告警失效但保留配置。 */
    private List<AlertRule> invalidateOrg(long orgId) {
        List<AlertRule> changed = new ArrayList<>();
        // rules: 遍历的容器需要遍历具体容器, 不要写类似 repository.byOrg(orgId) 这种的表达式
        for (AlertRule rule : repository.byOrg(orgId)) {
            // rules: 不要在循环里操作数据库, 尽量使用批量更新逻辑
            changed.add(repository.save(rule.withInvalid(true).withEnabled(false, null)));
        }
        return changed;
    }
    // rules: List 去重使用 stream 流, 不要单独再写一个函数
    private List<Long> distinct(List<Long> recipients) {
        if (recipients == null) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>(recipients);
        return List.copyOf(unique);
    }
}
