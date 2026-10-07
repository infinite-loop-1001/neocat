package com.neocat.alert.infra.jdbc;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        row.setFormulaStats(Objects.isNull(rule.getTarget().getFormulaStats()) ? "" :
                rule.getTarget().getFormulaStats().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
        row.setTargetStat(rule.getConditions().get(0).getStat().name());
        row.setChannels(rule.getChannels().stream().map(Enum::name)
                .collect(java.util.stream.Collectors.joining(",")));
        row.setTargetCardId(rule.getTarget().isCardResult() ? rule.getTarget().getCardId() : null);
        row.setCombinator(rule.getCombinator().name());
        row.setWindowPoints(rule.getWindowPoints());
        row.setEnabled(rule.isEnabled());
        row.setInvalid(rule.isInvalid());
        row.setStateSince(Objects.isNull(rule.getStateSince())
                ? null
                : java.sql.Timestamp.from(Instant.ofEpochMilli(rule.getStateSince())));

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
            if (Objects.nonNull(prior) && Objects.nonNull(prior.getOrgId()) && !prior.getOrgId().equals(rule.getOrgId())) {
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
    @org.springframework.transaction.annotation.Transactional
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
            mapper.insertWindowPoint(state.getRuleId(), java.sql.Timestamp.from(Instant.ofEpochMilli(minute)));
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
                ? List.of() : java.util.Arrays.stream(row.getChannels().split(","))
                .map(AlertChannel::valueOf).toList();

        AlertTarget target = new AlertTarget(
                AlertTargetKind.valueOf(row.getTargetKind()),
                Objects.isNull(row.getTargetCardId()) ? 0 : row.getTargetCardId(),
                row.getTargetService(),
                row.getReportKind(),
                row.getTargetType(),
                row.getTargetName(),
                row.getTargetMetricLabels(),
                Objects.isNull(row.getFormulaStats()) || row.getFormulaStats().isBlank() ? List.of() :
                        java.util.Arrays.stream(row.getFormulaStats().split(",")).map(Stat::parse).toList());

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
    /** 规则行。 */
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

        private java.sql.Timestamp stateSince;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public Long getOrgId() {
            return orgId;
        }

        public void setOrgId(Long orgId) {
            this.orgId = orgId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getReportKind() { return reportKind; }
        public void setReportKind(String reportKind) { this.reportKind = reportKind; }
        public String getTargetMetricLabels() { return targetMetricLabels; }
        public void setTargetMetricLabels(String targetMetricLabels) { this.targetMetricLabels = targetMetricLabels; }
        public String getFormulaStats() { return formulaStats; }
        public void setFormulaStats(String formulaStats) { this.formulaStats = formulaStats; }
        public String getTargetStat() { return targetStat; }
        public void setTargetStat(String targetStat) { this.targetStat = targetStat; }
        public String getChannels() { return channels; }
        public void setChannels(String channels) { this.channels = channels; }

        public String getTargetKind() {
            return targetKind;
        }

        public void setTargetKind(String targetKind) {
            this.targetKind = targetKind;
        }

        public String getTargetService() {
            return targetService;
        }

        public void setTargetService(String targetService) {
            this.targetService = targetService;
        }

        public String getTargetType() {
            return targetType;
        }

        public void setTargetType(String targetType) {
            this.targetType = targetType;
        }

        public String getTargetName() {
            return targetName;
        }

        public void setTargetName(String targetName) {
            this.targetName = targetName;
        }

        public Long getTargetCardId() {
            return targetCardId;
        }

        public void setTargetCardId(Long targetCardId) {
            this.targetCardId = targetCardId;
        }

        public String getCombinator() {
            return combinator;
        }

        public void setCombinator(String combinator) {
            this.combinator = combinator;
        }

        public int getWindowPoints() {
            return windowPoints;
        }

        public void setWindowPoints(int windowPoints) {
            this.windowPoints = windowPoints;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isInvalid() {
            return invalid;
        }

        public void setInvalid(boolean invalid) {
            this.invalid = invalid;
        }

        public java.sql.Timestamp getStateSince() {
            return stateSince;
        }

        public void setStateSince(java.sql.Timestamp stateSince) {
            this.stateSince = stateSince;
        }
    }
    /** 条件行。 */
    public static class AlertConditionRow {
        private String stat;

        private String comparator;

        private double threshold;

        public String getStat() {
            return stat;
        }

        public void setStat(String stat) {
            this.stat = stat;
        }

        public String getComparator() {
            return comparator;
        }

        public void setComparator(String comparator) {
            this.comparator = comparator;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }
    }
    /** 收件人行。 */
    public static class AlertRecipientRow {
        private long accountId;

        private String channel;

        public long getAccountId() {
            return accountId;
        }

        public void setAccountId(long accountId) {
            this.accountId = accountId;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }
    }
    /** 窗口点行。 */
    public static class AlertWindowPointRow {
        private java.sql.Timestamp pointMinute;

        private boolean satisfied;

        public java.sql.Timestamp getPointMinute() {
            return pointMinute;
        }

        public void setPointMinute(java.sql.Timestamp pointMinute) {
            this.pointMinute = pointMinute;
        }

        public boolean isSatisfied() {
            return satisfied;
        }

        public void setSatisfied(boolean satisfied) {
            this.satisfied = satisfied;
        }
    }
}















