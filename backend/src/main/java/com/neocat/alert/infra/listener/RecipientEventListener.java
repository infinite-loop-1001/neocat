package com.neocat.alert.infra.listener;

import com.neocat.alert.domain.recipient.RecipientService;
import com.neocat.identity.api.internal.AccountStatusChanged;
import com.neocat.organization.api.internal.EffectiveMembershipChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.neocat.alert.domain.recipient.OrgMembershipChanged;
import com.neocat.alert.domain.recipient.UserDisabled;
import com.neocat.alert.domain.recipient.UserEnabled;

// rules: infra 层监听外部领域事件的类命名规范为 ***EventListener, 若需要将外部领域事件转化为内部领域事件, 需要遵守下面的规则
//  1. ***EventListener 转换领域事件后不直接调用上层逻辑处理内部领域事件, 需要将内部领域事件发布到上层监听者处消费
//  2. 内部领域事件监听类命名规范为 ***EventHandler (可抽象, 即一个 Handler 处理多个相同类型但是内容不同的事件, 如用户 enable/disable)
//  3. ***EventHandler 需要在功能模块的 application 层 (controller -> application -> domain -> infra 若没有 app 层则创建) 的 handler 包下
@Component
public class RecipientEventListener {

    private final RecipientService recipients;

    public RecipientEventListener(RecipientService recipients) {
        this.recipients = recipients;
    }

    @EventListener
    public void on(AccountStatusChanged event) {
        recipients.onEvent(event.isEnabled() ? new UserEnabled(event.getAccountId())
                : new UserDisabled(event.getAccountId()));
    }

    @EventListener
    public void on(EffectiveMembershipChanged event) {
        recipients.onEvent(new OrgMembershipChanged(event.getAccountId(), event.getOrgId(), event.isGranted()));
    }
}
