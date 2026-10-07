package com.neocat.alert.domain.rule;

import com.neocat.alert.domain.engine.AlertWindowState;
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

    void saveWindowState(AlertWindowState state);

    void clearWindowState(long ruleId);
}
