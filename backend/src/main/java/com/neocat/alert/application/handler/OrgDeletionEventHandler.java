package com.neocat.alert.application.handler;

import com.neocat.alert.domain.repository.AlertRuleRepository;
import com.neocat.organization.domain.lifecycle.OrgDeletionRequested;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 叶子删除时级联删除该叶子的组织告警（PRD 01 §5.3、PRD 06 §12）。
 *
 * <p>与大盘、卡片一样是删除而非失效：调用方确认删除后在同一事务内完成，
 * 不保留「失效但保留配置」的规则。
 */
@Component
public class OrgDeletionEventHandler {
    private final AlertRuleRepository rules;

    public OrgDeletionEventHandler(AlertRuleRepository rules) {
        this.rules = rules;
    }
    @EventListener
    public void on(OrgDeletionRequested event) {
        rules.byOrg(event.getOrgId()).forEach(r -> rules.delete(r.getId()));
    }
}
