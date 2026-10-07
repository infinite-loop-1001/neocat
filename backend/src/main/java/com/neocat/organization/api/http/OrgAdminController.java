package com.neocat.organization.api.http;

import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.error.ErrorCode;
import com.neocat.common.http.context.RequestActor;
import com.neocat.organization.api.http.dto.OrgDtos.*;
import com.neocat.organization.api.http.convert.OrgConvert;
import com.neocat.organization.domain.lifecycle.OrgLifecycleService;
import com.neocat.organization.domain.membership.OrgMembershipService;
import com.neocat.organization.domain.tree.OrgNode;
import com.neocat.organization.domain.tree.OrgNodeRepository;
import com.neocat.organization.domain.tree.OrgTreeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 组织与成员管理 HTTP 入口；管理员检查不改变。 */
@RestController
@RequestMapping("/api")
public class OrgAdminController {
    private final OrgTreeService tree;

    private final OrgLifecycleService lifecycle;

    private final OrgMembershipService membership;

    private final OrgNodeRepository nodes;

    public OrgAdminController(OrgTreeService tree, OrgLifecycleService lifecycle,
                              OrgMembershipService membership, OrgNodeRepository nodes) {
        this.tree = tree;
        this.lifecycle = lifecycle;
        this.membership = membership;
        this.nodes = nodes;
    }

    @GetMapping("/orgs")
    public ResponseEntity<List<OrgResponse>> orgs() {
        return ResponseEntity.ok(nodes.findAll().stream().map(this::response).toList());
    }

    @GetMapping("/orgs/mine")
    public ResponseEntity<List<Long>> myLeaves(HttpServletRequest request) {
        return ResponseEntity.ok(List.copyOf(membership.effectiveLeaves(RequestActor.current(request).getId())));
    }

    @PostMapping("/orgs")
    public ResponseEntity<OrgResponse> createOrg(HttpServletRequest request, @RequestBody OrgDraft draft) {
        requireAdmin(request);
        return ResponseEntity.status(201).body(response(tree.createNode(draft.getName(), draft.getParentId())));
    }

    @PostMapping("/orgs/{id}/rename")
    public ResponseEntity<OrgResponse> renameOrg(HttpServletRequest request, @PathVariable long id,
                                                @RequestBody OrgDraft draft) {
        requireAdmin(request);
        return ResponseEntity.ok(response(tree.rename(id, draft.getName())));
    }

    @PostMapping("/orgs/{id}/members")
    public ResponseEntity<Success> addMember(HttpServletRequest request, @PathVariable long id,
                                             @RequestBody MemberDraft draft) {
        requireAdmin(request);
        membership.addMember(id, draft.getUserId());
        return ResponseEntity.ok(new Success(true));
    }

    @DeleteMapping("/orgs/{id}/members/{userId}")
    public ResponseEntity<Success> removeMember(HttpServletRequest request, @PathVariable long id,
                                                @PathVariable long userId) {
        requireAdmin(request);
        membership.removeMember(id, userId);
        return ResponseEntity.ok(new Success(true));
    }

    @GetMapping("/orgs/{id}/deletion-preview")
    public ResponseEntity<DeletionResponse> deletionPreview(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ResponseEntity.ok(OrgConvert.deletion(lifecycle.previewDeletion(id)));
    }

    @DeleteMapping("/orgs/{id}")
    public ResponseEntity<Success> deleteOrg(HttpServletRequest request, @PathVariable long id,
                                            @RequestParam String confirmName) {
        requireAdmin(request);
        lifecycle.deleteNode(id, confirmName);
        return ResponseEntity.ok(new Success(true));
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!RequestActor.current(request).isAdmin()) {
            throw new AuthorizationException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }

    private OrgResponse response(OrgNode node) {
        return OrgConvert.node(node, nodes.childrenOf(node.getId()).isEmpty(),
                membership.effectiveMembers(node.getId()).size());
    }
}
