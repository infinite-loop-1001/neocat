package com.neocat.organization.infra.projection;

import com.neocat.organization.api.internal.OrgResourceIndex;
import com.neocat.organization.domain.tree.DeletionPreview;
import com.neocat.organization.domain.lifecycle.OrgDeletionRequested;
import com.neocat.organization.domain.lifecycle.OrgResourceGateway;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Synchronous projection: reads never guess zero when persistence is unavailable. */
@Component
public class OrgResourceProjection implements OrgResourceGateway, OrgResourceIndex {
    private final JdbcTemplate jdbc;

    private final ApplicationEventPublisher events;

    public OrgResourceProjection(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }
    @Override
    public void dashboard(long orgId, long dashboardId, String name) {
        jdbc.update("""
                INSERT INTO nc_org_resource (org_id, resource_kind, resource_id, name)
                VALUES (?, 'DASHBOARD', ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name)
                """, orgId, dashboardId, name);
    }
    @Override
    public void removeDashboard(long orgId, long dashboardId) {
        jdbc.update("DELETE FROM nc_org_resource WHERE org_id = ? AND resource_kind = 'DASHBOARD' AND resource_id = ?",
                orgId, dashboardId);
    }
    @Override
    public void cardCount(long orgId, long dashboardId, long count) {
        int updated = jdbc.update("""
                UPDATE nc_org_resource SET card_count = ?
                WHERE org_id = ? AND resource_kind = 'DASHBOARD' AND resource_id = ?
                """, count, orgId, dashboardId);
        if (updated == 0 && jdbc.queryForObject("""
                SELECT COUNT(*) FROM nc_org_resource
                WHERE org_id = ? AND resource_kind = 'DASHBOARD' AND resource_id = ?
                """, Long.class, orgId, dashboardId) != 1L) {
            throw new IllegalStateException("Dashboard projection is missing: " + dashboardId);
        }
    }
    @Override
    public void alertRule(long orgId, long ruleId) {
        jdbc.update("""
                INSERT INTO nc_org_resource (org_id, resource_kind, resource_id, name)
                VALUES (?, 'ALERT_RULE', ?, '')
                ON DUPLICATE KEY UPDATE resource_id = VALUES(resource_id)
                """, orgId, ruleId);
    }
    @Override
    public void removeAlertRule(long orgId, long ruleId) {
        jdbc.update("DELETE FROM nc_org_resource WHERE org_id = ? AND resource_kind = 'ALERT_RULE' AND resource_id = ?",
                orgId, ruleId);
    }
    private long count(long orgId, String kind) {
        long projected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nc_org_resource WHERE org_id = ? AND resource_kind = ?",
                Long.class, orgId, kind);
        // Treat a missing or stale projection as a fault, not as proof that the leaf is empty.
        String source = switch (kind) {
            case "DASHBOARD" -> "SELECT COUNT(*) FROM nc_dashboard WHERE org_id = ?";
            case "ALERT_RULE" -> "SELECT COUNT(*) FROM nc_alert_rule WHERE org_id = ?";
            default -> throw new IllegalArgumentException("Unknown resource kind: " + kind);
        };
        long actual = jdbc.queryForObject(source, Long.class, orgId);
        if (projected != actual) {
            throw new IllegalStateException("Organization resource projection is inconsistent: " + orgId);
        }
        String missing = switch (kind) {
            case "DASHBOARD" -> """
                    SELECT COUNT(*) FROM nc_dashboard d LEFT JOIN nc_org_resource r
                        ON r.org_id = d.org_id AND r.resource_kind = 'DASHBOARD' AND r.resource_id = d.id
                    WHERE d.org_id = ? AND (r.resource_id IS NULL OR r.name <> d.name OR
                        r.card_count <> (SELECT COUNT(*) FROM nc_card c WHERE c.dashboard_id = d.id))
                    """;
            case "ALERT_RULE" -> """
                    SELECT COUNT(*) FROM nc_alert_rule a LEFT JOIN nc_org_resource r
                        ON r.org_id = a.org_id AND r.resource_kind = 'ALERT_RULE' AND r.resource_id = a.id
                    WHERE a.org_id = ? AND r.resource_id IS NULL
                    """;
            default -> throw new IllegalArgumentException("Unknown resource kind: " + kind);
        };
        if (jdbc.queryForObject(missing, Long.class, orgId) != 0L) {
            throw new IllegalStateException("Organization resource projection has missing or stale entries: " + orgId);
        }
        return projected;
    }
    @Override
    public boolean hasDashboards(long orgId) { return count(orgId, "DASHBOARD") > 0; }

    @Override
    public boolean hasAlertRules(long orgId) { return count(orgId, "ALERT_RULE") > 0; }

    @Override
    public List<DeletionPreview.DashboardSummary> dashboardsOf(long orgId) {
        long expected = count(orgId, "DASHBOARD");
        var dashboards = jdbc.query("""
                SELECT r.resource_id, r.name, r.card_count
                FROM nc_org_resource r
                WHERE r.org_id = ? AND r.resource_kind = 'DASHBOARD'
                ORDER BY r.resource_id
                """, (rs, index) -> new DeletionPreview.DashboardSummary(
                rs.getLong("resource_id"), rs.getString("name"), rs.getLong("card_count")), orgId);
        if (dashboards.size() != expected) {
            throw new IllegalStateException("Dashboard projection changed during preview: " + orgId);
        }
        return dashboards;
    }
    @Override
    public long alertRuleCount(long orgId) { return count(orgId, "ALERT_RULE"); }

    @Override
    public void deleteAllOf(long orgId) {
        // Never let deletion hide a missing or stale projection by removing the source rows first.
        hasDashboards(orgId);
        hasAlertRules(orgId);
        events.publishEvent(new OrgDeletionRequested(orgId));
        // Listeners run synchronously; fail closed and roll back if any resource remains.
        if (hasDashboards(orgId) || hasAlertRules(orgId)) {
            throw new IllegalStateException("Organization resources were not fully removed: " + orgId);
        }
    }
    @Override
    public void invalidateAlertRulesOf(long orgId) {
        // The deletion event removes isOrganization rules; no dangling rule remains to invalidate.
        if (hasAlertRules(orgId)) {
            throw new IllegalStateException("Organization alert rules remain: " + orgId);
        }
    }
}
