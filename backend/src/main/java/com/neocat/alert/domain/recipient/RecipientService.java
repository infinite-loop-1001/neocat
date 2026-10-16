package com.neocat.alert.domain.recipient;

import com.google.common.collect.Lists;
import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor;
import com.neocat.alert.domain.entity.AlertRule;
import com.neocat.alert.domain.gateway.RecipientGateway;
import com.neocat.alert.domain.repository.AlertRuleRepository;
import com.neocat.alert.domain.AlertRuleService;
import com.neocat.common.locking.MySqlLocked;
import org.apache.commons.collections4.ListUtils;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.collections4.CollectionUtils;
import java.util.Objects;
import java.util.NoSuchElementException;

/**
 * 收件人维护（PRD 06 §9）。
 *
 * <p>事件处理规则：账号禁用/失去组织资格只做**移除**，启用/获得资格**不自动加入**。
 * 移除动作由 {@code RecipientEventHandler} 直接消费外部领域事件后完成；本类只保留收件人校验与窗口重建。
 *
 * <p>组织删除不在这里处理：叶子被删除时组织告警随大盘、卡片一并级联删除
 * （PRD 01 §5.3、PRD 06 §12），由 {@code OrgDeletionEventHandler} 编排，
 * 不是「失效但保留」。
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

    private final AlertRuleRepository alertRuleRepository;

    private final RecipientGateway recipientGateway;

    public RecipientService(AlertRuleRepository alertRuleRepository, RecipientGateway recipientGateway) {
        this.alertRuleRepository = alertRuleRepository;
        this.recipientGateway = recipientGateway;
    }

    /**
     * 计算某规则当前的有效收件人。
     *
     * <p>组织告警要求收件人仍是该叶子的有效成员；服务告警原样保留。
     * 禁用账号在禁用时就已由 {@code RecipientEventHandler} 从规则中移除，
     * 因此这里不再重复查询启用状态。
     *
     * <p>规则本身不因收件人清空而关闭。
     */
    public List<String> effectiveRecipients(AlertRule rule) {
        if (Objects.isNull(rule) || CollectionUtils.isEmpty(rule.getRecipients())) {
            return Lists.newArrayList();
        }
        List<String> result = new ArrayList<>();
        for (String userAccount : rule.getRecipients()) {
            if (rule.isOrganization() && Objects.nonNull(rule.getOrgId())
                    && !recipientGateway.isEffectiveMember(userAccount, rule.getOrgId())) {
                continue;
            }
            result.add(userAccount);
        }
        return List.copyOf(result);
    }

    /**
     * 补充收件人：**重建窗口基线**，不追溯旧异常（PRD 06 §9）。
     */
    @MySqlLocked("metadata")
    public AlertRule updateRecipients(long ruleId, List<String> userAccounts, long addedAt) {
        AlertRule rule = Optional.ofNullable(alertRuleRepository.findById(ruleId))
                .orElseThrow(() -> new NoSuchElementException("规则不存在：" + ruleId));
        List<String> sanitized = ListUtils.emptyIfNull(userAccounts).stream().distinct().toList();

        AlertRule updated = rule.withRecipients(sanitized);
        AlertRule saved = alertRuleRepository.save(updated);

        // 窗口重建：新基线为补充时刻，点集为空
        // fixme: 先修改 AlterRule 再和告警窗口通过领域事件交互
        alertRuleRepository.clearAlertEvalCursor(ruleId);
        alertRuleRepository.saveAlertEvalCursor(AlertEvalCursor.empty(ruleId, addedAt));
        return saved;
    }
    /** 校验收件人选择合法：账号必须启用；组织告警还必须是该叶子的有效成员。 */
    public void validateSelection(AlertRule rule, List<String> userAccounts) {
        if (CollectionUtils.isEmpty(userAccounts)) {
            return;
        }
        // fixme: 这里是逐个账号做 RPC, 后续应改成批量校验账号启用状态与成员资格
        for (String userAccount : userAccounts) {
            if (!recipientGateway.isEnabled(userAccount)) {
                throw new IllegalArgumentException("收件人必须是启用状态的账号：" + userAccount);
            }
            if (rule.isOrganization() && Objects.nonNull(rule.getOrgId())
                    && !recipientGateway.isEffectiveMember(userAccount, rule.getOrgId())) {
                throw new IllegalArgumentException("组织告警的收件人必须是该叶子的有效成员：" + userAccount);
            }
        }
    }
}
