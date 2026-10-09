package com.neocat.alert.domain.rule;

import com.neocat.query.domain.stat.Stat;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;
import java.util.List;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;

/**
 * 一条告警规则（PRD 06 §1、§2）。
 *
 * <p>结构约束：
 * <ul>
 *   <li>**一条规则只绑定一个目标时间序列**；</li>
 *   <li>可含多个比较条件，但所有条件作用于同一目标；</li>
 *   <li>连接符统一 {@code AND} 或统一 {@code OR}；</li>
 *   <li>**滑动窗口长度 X 属于整条规则**，单位为完整分钟点；</li>
 *   <li>**保存后始终为关闭状态**，必须手动启用。</li>
 * </ul>
 *
 * fixme: param 找不到
 * @param id          规则 ID；0 表示尚未持久化
 * @param scope       作用范围
 * @param orgId       组织告警所属叶子；服务告警为 null
 * @param name        规则名称
 * @param description 描述
 * @param target      目标序列
 * @param combinator  统一 AND / OR
 * @param windowPoints 整条规则共用的滑动窗口长度 X
 * @param conditions  比较条件；至少一个
 * @param recipients  收件人账号 ID
 * @param channels    通知通道
 * @param enabled     是否启用；**新建与编辑后均为 false**
 * @param invalid     目标失效（如卡片被删）但保留配置
 * @param stateSince  启用时刻；窗口只使用该时刻之后的完整分钟点
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
// fixme: AlterRule 定位是规则聚合, 可以修改内部数据, 外部变更不需要像值对象那样 copy on write
public class AlertRule {
    private final long id;

    private final AlertScope scope;

    private final Long orgId;

    private final String name;

    private final String description;

    private final AlertTarget target;

    private final Combinator combinator;

    private final int windowPoints;

    private final List<Condition> conditions;

    private final List<Long> recipients;

    private final List<AlertChannel> channels;

    private final boolean enabled;

    private final boolean invalid;

    // fixme: 规则里不应该有启用的具体时间, 这个应该交给监控窗口管理
    private final Long stateSince;

    public AlertRule(long id, AlertScope scope, Long orgId,
                     String name, String description, AlertTarget target,
                     Combinator combinator,
                     int windowPoints, List<Condition> conditions,
                     List<Long> recipients, List<AlertChannel> channels,
                     boolean enabled, boolean invalid, Long stateSince) {
        this.id = id;
        this.scope = scope;
        this.orgId = orgId;
        this.name = name;
        this.description = description;
        this.target = target;
        this.combinator = combinator;
        this.windowPoints = windowPoints;
        this.conditions = conditions;
        this.recipients = recipients;
        this.channels = channels;
        this.enabled = enabled;
        this.invalid = invalid;
        this.stateSince = stateSince;
    }

    /** 新建规则的初始状态：关闭、未失效、无窗口基线。 */
    public static AlertRule draft(AlertScope scope, Long orgId, String name, String description,
                                  AlertTarget target, Combinator combinator, int windowPoints,
                                  List<Condition> conditions, List<Long> recipients,
                                  List<AlertChannel> channels) {
        return new AlertRule(0, scope, orgId, name, description, target, combinator, windowPoints,
                List.copyOf(conditions), List.copyOf(recipients), List.copyOf(channels),
                false, false, null);
    }
    public boolean isOrganization() {
        return Objects.equals(scope, AlertScope.ORGANIZATION);
    }
    /** 规则引用的统计项（用于判断目标是否仍被卡片引用）。 */
    public List<Stat> referencedStats() {
        if (Objects.nonNull(target) && Objects.nonNull(target.getFormulaStats()) && CollectionUtils.isNotEmpty(target.getFormulaStats())) {
            return target.getFormulaStats();
        }
        return conditions.stream().map(Condition::getStat).distinct().toList();
    }
    public AlertRule withEnabled(boolean newEnabled, Long newStateSince) {
        return new AlertRule(id, scope, orgId, name, description, target, combinator, windowPoints,
                conditions, recipients, channels, newEnabled, invalid, newStateSince);
    }
    public AlertRule withInvalid(boolean newInvalid) {
        return new AlertRule(id, scope, orgId, name, description, target, combinator, windowPoints,
                conditions, recipients, channels, enabled, newInvalid, stateSince);
    }
    public AlertRule withId(long newId) {
        return new AlertRule(newId, scope, orgId, name, description, target, combinator, windowPoints,
                conditions, recipients, channels, enabled, invalid, stateSince);
    }
    public AlertRule withRecipients(List<Long> newRecipients) {
        return new AlertRule(id, scope, orgId, name, description, target, combinator, windowPoints,
                conditions, List.copyOf(newRecipients), channels, enabled, invalid, stateSince);
    }
}