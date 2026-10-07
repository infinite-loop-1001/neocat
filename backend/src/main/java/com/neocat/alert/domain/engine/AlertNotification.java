package com.neocat.alert.domain.engine;

import com.neocat.alert.domain.rule.AlertChannel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

import java.util.List;

/**
 * 一次通知投递（PRD 06 §10）。
 *
 * <p>**仅存在于运行期**：一期不保存正式告警触发事件历史。
 *
 * fixme: param 找不到
 * @param ruleId     规则 ID
 * @param ruleName   规则名
 * @param recipients 有效收件人账号 ID
 * @param channel    目标通道
 * @param message    通知内容
 * @param triggeredAt 触发分钟点
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public class AlertNotification {
    private final long ruleId;

    private final String ruleName;

    private final List<Long> recipients;

    private final AlertChannel channel;

    private final String message;

    private final long triggeredAt;

    public AlertNotification(
            long ruleId, String ruleName, List<Long> recipients,
            AlertChannel channel, String message, long triggeredAt) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.recipients = recipients;
        this.channel = channel;
        this.message = message;
        this.triggeredAt = triggeredAt;
    }

}



