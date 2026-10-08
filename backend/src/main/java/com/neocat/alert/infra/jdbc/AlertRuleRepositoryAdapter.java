package com.neocat.alert.infra.jdbc;

import java.math.BigDecimal;

import com.google.common.collect.Lists;
import com.neocat.alert.domain.rule.AlertChannel;
import com.neocat.alert.domain.rule.AlertRule;
import com.neocat.alert.domain.rule.AlertRuleRepository;
import com.neocat.alert.domain.rule.AlertScope;
import com.neocat.alert.domain.rule.AlertTarget;
import com.neocat.alert.domain.rule.AlertTargetKind;
import com.neocat.alert.domain.engine.AlertWindowState;
import com.neocat.alert.domain.rule.Combinator;
import com.neocat.alert.domain.rule.Comparator;
import com.neocat.alert.domain.rule.Condition;
import com.neocat.query.domain.stat.Stat;
import com.neocat.organization.api.internal.OrgResourceIndex;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;

import java.sql.Timestamp;
import java.util.stream.Collectors;

/**
 * 告警规则与窗口状态的 MyBatis 适配器
 * （表 {@code nc_alert_rule} / {@code nc_alert_condition} / {@code nc_alert_recipient} /
 * {@code nc_alert_window_state}）。
 *
 * <p>关键约束（PRD 06 §11）：**不存在任何触发历史表**。
 * 一期只保存规则本身、启停状态与滑动窗口的点集合，不保存告警事件。
 */
@Repository
public class AlertRuleRepositoryAdapter implements AlertRuleRepository {

    private final AlertMapper mapper;

    private final OrgResourceIndex resources;

    public AlertRuleRepositoryAdapter(AlertMapper mapper) {
        this(mapper, null);
    }

    // rules: 使用构造函数注入, 不要用 autowire
    @Autowired
    public AlertRuleRepositoryAdapter(AlertMapper mapper, OrgResourceIndex resources) {
        this.mapper = mapper;
        this.resources = resources;
    }

    @Override
    @Transactional
    public AlertRule save(AlertRule rule) {
        AlertRuleRow prior = rule.getId() == 0 ? null : mapper.selectRule(rule.getId());
        AlertRuleRow row = new AlertRuleRow();
        row.setId(rule.getId() == 0 ? null : rule.getId());
        row.setScope(rule.getScope().name());
        row.setOrgId(rule.getOrgId());
        row.setName(rule.getName());
        row.setDescription(rule.getDescription());
        row.setTargetKind(rule.getTarget().getKind().name());
        row.setReportKind(rule.getTarget().getReportKind());
        row.setTargetService(rule.getTarget().getService());
        row.setTargetType(rule.getTarget().getType());
        row.setTargetName(rule.getTarget().getName());
        row.setTargetMetricLabels(rule.getTarget().getMetricLabels());
        row.setFormulaStats(CollectionUtils.isEmpty(rule.getTarget().getFormulaStats()) ? "" :
                rule.getTarget().getFormulaStats().stream().map(Enum::name).collect(Collectors.joining(",")));
        row.setTargetStat(rule.getConditions().get(0).getStat().name());
        row.setChannels(rule.getChannels().stream().map(Enum::name)
                .collect(Collectors.joining(",")));
        row.setTargetCardId(rule.getTarget().isCardResult() ? rule.getTarget().getCardId() : null);
        row.setCombinator(rule.getCombinator().name());
        row.setWindowPoints(rule.getWindowPoints());
        row.setEnabled(rule.isEnabled());
        row.setInvalid(rule.isInvalid());
        row.setStateSince(Objects.isNull(rule.getStateSince())
                ? null
                : Timestamp.from(Instant.ofEpochMilli(rule.getStateSince())));

        if (Objects.isNull(row.getId())) {
            mapper.insertRule(row);
        } else {
            mapper.updateRule(row);
        }

        long ruleId = row.getId();
        // 条件与收件人整体替换：编辑时旧集合必须被清掉，避免残留条件继续参与判定
        mapper.deleteConditions(ruleId);
        for (Condition condition : rule.getConditions()) {
            mapper.insertCondition(ruleId, condition.getStat().name(),
                    condition.getComparator().name(), condition.getThreshold());
        }
        mapper.deleteRecipients(ruleId);
        for (AlertChannel channel : rule.getChannels()) {
            for (Long recipient : rule.getRecipients()) {
                mapper.insertRecipient(ruleId, recipient, channel.name());
            }
        }

        if (Objects.nonNull(resources)) {
            if (Objects.nonNull(prior) && Objects.nonNull(prior.getOrgId()) && !Objects.equals(prior.getOrgId(), rule.getOrgId())) {
                resources.removeAlertRule(prior.getOrgId(), rule.getId());
            }
            if (rule.isOrganization()) {
                resources.alertRule(rule.getOrgId(), ruleId);
            }
        }

        return rule.getId() == 0 ? rule.withId(ruleId) : rule;
    }

    @Override
    public AlertRule findById(long ruleId) {
        AlertRuleRow row = mapper.selectRule(ruleId);
        return Objects.isNull(row) ? null : toDomain(row);
    }

    @Override
    public List<AlertRule> findAll() {
        return mapper.selectAllRules().stream().map(this::toDomain).toList();
    }

    @Override
    public List<AlertRule> enabledRules() {
        return mapper.selectEnabledRules().stream().map(this::toDomain).toList();
    }

    @Override
    public List<AlertRule> byOrg(long orgId) {
        return mapper.selectRulesByOrg(orgId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void delete(long ruleId) {
        AlertRuleRow existing = mapper.selectRule(ruleId);
        // 表定义无外键，级联必须在同一事务内按依赖顺序显式删除：
        // 条件 → 收件人 → 窗口状态 → 规则，避免留下悬挂引用。
        mapper.deleteConditions(ruleId);
        mapper.deleteRecipients(ruleId);
        mapper.deleteWindowPoints(ruleId);
        mapper.deleteRule(ruleId);
        if (Objects.nonNull(resources) && Objects.nonNull(existing) && Objects.nonNull(existing.getOrgId())) {
            resources.removeAlertRule(existing.getOrgId(), ruleId);
        }
    }

    @Override
    public void saveWindowState(AlertWindowState state) {
        mapper.deleteWindowPoints(state.getRuleId());
        for (Long minute : state.getPoints()) {
            mapper.insertWindowPoint(state.getRuleId(), Timestamp.from(Instant.ofEpochMilli(minute)));
        }
    }

    @Override
    public void clearWindowState(long ruleId) {
        mapper.deleteWindowPoints(ruleId);
    }

    // ── 转换 ─────────────────────────────────────────────────

    private AlertRule toDomain(AlertRuleRow row) {
        List<Condition> conditions = new ArrayList<>();
        for (AlertConditionRow condition : mapper.selectConditions(row.getId())) {
            conditions.add(new Condition(Stat.valueOf(condition.getStat()),
                    Comparator.valueOf(condition.getComparator()), condition.getThreshold()));
        }
        List<AlertRecipientRow> recipients = mapper.selectRecipients(row.getId());
        List<Long> recipientIds = recipients.stream().map(AlertRecipientRow::getAccountId).distinct().toList();
        List<AlertChannel> channels = Objects.isNull(row.getChannels()) || row.getChannels().isBlank()
                ? Lists.newArrayList() : Arrays.stream(row.getChannels().split(","))
                .map(AlertChannel::valueOf).toList();

        AlertTarget target = new AlertTarget(
                AlertTargetKind.valueOf(row.getTargetKind()),
                Objects.isNull(row.getTargetCardId()) ? 0 : row.getTargetCardId(),
                row.getTargetService(),
                row.getReportKind(),
                row.getTargetType(),
                row.getTargetName(),
                row.getTargetMetricLabels(),
                Objects.isNull(row.getFormulaStats()) || row.getFormulaStats().isBlank() ? Lists.newArrayList() :
                        Arrays.stream(row.getFormulaStats().split(",")).map(Stat::parse).toList());

        return new AlertRule(
                row.getId(),
                AlertScope.valueOf(row.getScope()),
                row.getOrgId(),
                row.getName(),
                row.getDescription(),
                target,
                Combinator.valueOf(row.getCombinator()),
                row.getWindowPoints(),
                conditions,
                recipientIds,
                channels,
                row.isEnabled(),
                row.isInvalid(),
                Objects.isNull(row.getStateSince()) ? null : row.getStateSince().toInstant().toEpochMilli());
    }

    /**
     * 规则行。
     */
    @Data
    public static class AlertRuleRow {

        private Long id;

        private String scope;

        private Long orgId;

        private String name;

        private String description;

        private String targetKind;

        private String reportKind;

        private String targetService;

        private String targetType;

        private String targetName;

        private String targetMetricLabels;

        private String formulaStats;

        private String targetStat;

        private String channels;

        private Long targetCardId;

        private String combinator;

        private int windowPoints;

        private boolean enabled;

        private boolean invalid;

        private Timestamp stateSince;
    }

    /**
     * 条件行。
     */
    @Data
    public static class AlertConditionRow {

        private String stat;

        private String comparator;

        private BigDecimal threshold;
    }

    /**
     * 收件人行。
     */
    @Data

    public static class AlertRecipientRow {

        private long accountId;

        private String channel;

    }

    /**
     * 窗口点行。
     */
    @Data
    public static class AlertWindowPointRow {

        private Timestamp pointMinute;

        private boolean satisfied;

    }
}







