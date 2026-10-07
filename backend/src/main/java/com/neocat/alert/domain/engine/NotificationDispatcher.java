package com.neocat.alert.domain.engine;

import com.neocat.alert.domain.rule.AlertChannel;
import com.neocat.alert.domain.rule.AlertRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 通知分发（PRD 06 §10、§11）。
 *
 * <p>正式触发流程（PRD 06 §10）：
 * <pre>
 * 窗口满足
 * → 直接向当前有效接收人发送
 * → 外部通道失败写运行日志
 * → 不保存站内触发事件
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>校验在保存期</b>：{@link #validateChannels} 拒绝未配置的通道与空通道列表，
 *       因此运行期不会出现「规则引用了不可用通道」的状态；</li>
 *   <li><b>逐通道独立 try/catch</b>：单个通道故障被记录后继续发送其余通道，
 *       避免一个通道挂掉掩盖其他通道；</li>
 *   <li><b>只有日志出口</b>：失败信息经 {@link DeliveryLogger} 写出，
 *       本类不持有任何历史容器，产品页面也就无从查询投递历史（PRD 06 §11）。</li>
 * </ul>
 */
@Service
@NamedInterface("alert")
public class NotificationDispatcher {

    private final ChannelAvailability availability;

    private final DeliveryLogger logger;

    private final Notifier notifier;

    public NotificationDispatcher(ChannelAvailability availability, DeliveryLogger logger) {
        this(availability, logger, Notifier.noop());
    }

    @Autowired
    public NotificationDispatcher(ChannelAvailability availability, DeliveryLogger logger, Notifier notifier) {
        this.availability = availability;
        this.logger = logger;
        this.notifier = notifier;
    }
    /**
     * 校验收件人与通道选择是否可用于保存。
     *
     * @throws IllegalArgumentException 通道未配置或列表为空
     */
    public void validateChannels(List<AlertChannel> channels) {
        if (Objects.isNull(channels) || channels.isEmpty()) {
            throw new IllegalArgumentException("规则必须指定至少一个通知通道");
        }
        for (AlertChannel channel : channels) {
            if (!availability.available(channel)) {
                throw new IllegalArgumentException("通道未配置，不可选择：" + channel);
            }
        }
    }
    /** 当前可在规则中选择的通道。 */
    public List<AlertChannel> selectableChannels() {
        List<AlertChannel> result = new ArrayList<>();
        for (AlertChannel channel : AlertChannel.values()) {
            if (availability.available(channel)) {
                result.add(channel);
            }
        }
        return List.copyOf(result);
    }
    /**
     * 向有效收件人发送通知。
     *
     * <p>逐通道独立发送：单个通道失败被记录并继续处理其余通道。
     *
     * @return 实际发送成功的通知；无有效收件人时为空列表
     */
    public List<AlertNotification> dispatch(AlertRule rule, List<Long> effectiveRecipients, long triggeredAt) {
        if (Objects.isNull(rule) || Objects.isNull(effectiveRecipients) || effectiveRecipients.isEmpty()) {
            return List.of();
        }
        if (Objects.isNull(rule.getChannels()) || rule.getChannels().isEmpty()) {
            return List.of();
        }

        String message = rule.getName() + " 触发于 " + triggeredAt;
        List<AlertNotification> sent = new ArrayList<>();

        for (AlertChannel channel : rule.getChannels()) {
            if (!availability.available(channel)) {
                continue;
            }
            AlertNotification notification = new AlertNotification(
                    rule.getId(), rule.getName(), List.copyOf(effectiveRecipients), channel, message, triggeredAt);
            try {
                notifier.send(notification);
                sent.add(notification);
            } catch (Throwable t) {
                // 失败只写运行日志：不抛异常、不中断其他通道、不落任何产品可见记录
                logger.failed(rule.getId(), channel, t, message);
            }
        }
        return List.copyOf(sent);
    }
}
