package com.neocat.organization.api.http.convert;

import com.neocat.organization.api.http.dto.OrgDtos.*;
import com.neocat.organization.domain.tree.OrgNode;
import com.neocat.organization.domain.tree.DeletionPreview;

public final class OrgConvert {
    private OrgConvert() {
    }

    public static OrgResponse node(OrgNode node, boolean leaf, int memberCount) {
        return new OrgResponse(node.getId(), node.getName(), node.getParentId(), leaf, memberCount);
    }

    public static DeletionResponse deletion(DeletionPreview preview) {
        return new DeletionResponse(preview.getOrgName(), preview.getDashboards().stream()
                .map(d -> new DashboardSummary(d.getId(), d.getName(), d.getCardCount())).toList(),
                preview.getAlertRuleCount(), preview.getMemberCount());
    }
}
