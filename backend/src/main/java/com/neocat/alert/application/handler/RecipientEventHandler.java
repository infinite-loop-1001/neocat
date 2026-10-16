package com.neocat.alert.application.handler;

import com.neocat.alert.domain.entity.AlertRule;
import com.neocat.alert.domain.repository.AlertRuleRepository;
import com.neocat.identity.api.internal.AccountStatusChanged;
import com.neocat.organization.api.internal.EffectiveMembershipChanged;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

// rules: 监听外部领域事件的类命名规范为 ***EventHandler, 需要遵守下面的规则
//  1. 需要抽象, 即一个 Handler 处理多个相同类型但是内容不同的事件, 如用户 enable/disable)
//  2. ***EventHandler 需要在功能模块的 application 层 (controller -> application -> domain -> infra 若没有 app 层则创建) 的 handler 包下
/**
 * 收件人维护（PRD 06 §9）：直接消费 identity / organization 的原始领域事件。
 *
 * <table border="1">
 *   <caption>事件 → 收件人变化</caption>
 *   <tr><th>事件</th><th>动作</th></tr>
 *   <tr><td>账号禁用</td><td>从所有规则移除该账号；<b>规则不关闭</b></td></tr>
 *   <tr><td>账号启用</td><td><b>不做任何事</b>（不恢复收件关系）</td></tr>
 *   <tr><td>失去组织资格</td><td>从该组织的组织告警中移除；服务告警不受影响</td></tr>
 *   <tr><td>获得组织资格</td><td><b>不自动加入</b>（只做移除，不做自动加入）</td></tr>
 * </table>
 *
 * <p>「只做移除，不做自动加入」是有意为之：自动加入会让用户在不知情的情况下开始接收告警。
 *
 * <p>fixme: 移除收件人本质是告警规则的领域行为，暂时留在 Handler 里编排；
 *  等确认它会被其它入口复用时再下沉到告警规则领域层。
 */
@Component
public class RecipientEventHandler {

    private final AlertRuleRepository alertRuleRepository;

    public RecipientEventHandler(AlertRuleRepository rules) {
        this.alertRuleRepository = rules;
    }

    /** 账号启用/禁用：只做移除，启用不恢复收件关系（PRD 06 §9）。 */
    @EventListener
    public void on(AccountStatusChanged event) {
        if (Objects.isNull(event) || event.isEnabled()) {
            return;
        }
        removeFrom(alertRuleRepository::findAll, event.getUserAccount());
    }

    /** 有效叶子成员资格变化：失去资格时从该叶子的组织告警中移除。 */
    @EventListener
    public void on(EffectiveMembershipChanged event) {
        if (Objects.isNull(event) || event.isGranted()) {
            return;
        }
        removeFrom(() -> alertRuleRepository.byOrg(event.getOrgId()), event.getUserAccount());
    }

    /**
     * 从候选规则中移除该账号的收件关系；规则不因收件人清空而关闭。
     */
    private void removeFrom(Supplier<List<AlertRule>> candidates, String userAccount) {
        for (AlertRule rule : candidates.get()) {
            if (CollectionUtils.isEmpty(rule.getRecipients()) || !rule.getRecipients().contains(userAccount)) {
                continue;
            }
            List<String> remaining = rule.getRecipients().stream()
                    .filter(recipient -> !Objects.equals(recipient, userAccount))
                    .toList();
            alertRuleRepository.save(rule.withRecipients(remaining));
        }
    }
}
