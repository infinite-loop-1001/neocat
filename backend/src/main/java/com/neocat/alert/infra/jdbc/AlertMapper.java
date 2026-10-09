package com.neocat.alert.infra.jdbc;

import java.math.BigDecimal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 告警 Mapper（表 {@code nc_alert_rule} / {@code nc_alert_condition} /
 * {@code nc_alert_recipient} / {@code nc_alert_window_state}）。
 *
 * <p>**注意：没有告警历史表**。PRD 06 §11 明确一期不保存正式触发事件，
 * 因此这里只提供规则、条件、收件人与滑动窗口点的读写。
 */
@Mapper
public interface AlertMapper {

    AlertRuleRow selectRule(@Param("id") long id);

    List<AlertRuleRow> selectAllRules();

    List<AlertRuleRow> selectEnabledRules();

    List<AlertRuleRow> selectRulesByOrg(@Param("orgId") long orgId);

    int insertRule(AlertRuleRow row);

    int updateRule(AlertRuleRow row);

    void deleteRule(@Param("id") long id);

    List<AlertConditionRow> selectConditions(@Param("ruleId") long ruleId);

    int insertCondition(@Param("ruleId") long ruleId,
                        @Param("stat") String stat,
                        @Param("comparator") String comparator,
                        @Param("threshold") BigDecimal threshold);

    void deleteConditions(@Param("ruleId") long ruleId);

    List<AlertRecipientRow> selectRecipients(@Param("ruleId") long ruleId);

    int insertRecipient(@Param("ruleId") long ruleId,
                        @Param("accountId") long accountId,
                        @Param("channel") String channel);

    void deleteRecipients(@Param("ruleId") long ruleId);

    List<AlertWindowPointRow> selectWindowPoints(@Param("ruleId") long ruleId);

    void insertWindowPoint(@Param("ruleId") long ruleId, @Param("pointMinute") Timestamp pointMinute);

    // fixme: 这里没有进行 MySQL 实现
    void deleteWindowPoints(@Param("ruleId") long ruleId);
}
