package com.neocat.alert.domain.repository;

import com.neocat.alert.domain.entity.AlertRule;
import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor;
import org.springframework.modulith.NamedInterface;

import java.util.List;

/**
 * 告警规则仓库。
 *
 * <p>一期只保存规则本身与启停状态，**不保存任何触发事件历史**（PRD 06 §11）。
 */
@NamedInterface("alert")
public interface AlertRuleRepository {

    AlertRule save(AlertRule rule);

    AlertRule findById(long ruleId);

    List<AlertRule> findAll();

    List<AlertRule> enabledRules();

    List<AlertRule> byOrg(long orgId);

    void delete(long ruleId);

    void saveAlertEvalCursor(AlertEvalCursor state);

    AlertEvalCursor findAlertEvalCursor(long ruleId);

    void clearAlertEvalCursor(long ruleId);
}
