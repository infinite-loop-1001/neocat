package com.neocat.organization.api.http;

import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.error.ErrorCode;
import com.neocat.common.http.context.RequestActor;
import com.neocat.organization.api.http.dto.*;
import com.neocat.organization.api.http.convert.OrgConvert;
import com.neocat.organization.domain.lifecycle.OrgLifecycleService;
import com.neocat.organization.domain.membership.OrgMembershipService;
import com.neocat.organization.domain.tree.OrgNode;
import com.neocat.organization.domain.tree.OrgNodeRepository;
import com.neocat.organization.domain.tree.OrgTreeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import org.apache.commons.collections4.CollectionUtils;
import com.neocat.organization.api.http.dto.DeletionResponse;
import com.neocat.organization.api.http.dto.MemberDraft;
import com.neocat.organization.api.http.dto.OrgDraft;
import com.neocat.organization.api.http.dto.OrgResponse;
import com.neocat.organization.api.http.dto.Success;

/** 组织与成员管理 HTTP 入口；管理员检查不改变。 */
@Tag(name = "组织与成员", description = "组织树维护、成员关系与删除预检")
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

    @Operation(operationId = "listOrgs", summary = "查询组织树",
            description = "返回全部节点及 leaf 标记与成员数；无组织权限的账号只看到空的管理入口。")
    @ApiResponse(responseCode = "200", description = "组织节点列表")
    @GetMapping("/orgs")
    public ResponseEntity<List<OrgResponse>> orgs() {
        return ResponseEntity.ok(nodes.findAll().stream().map(this::response).toList());
    }

    @Operation(operationId = "listMyLeafOrgs", summary = "查询当前账号有权限的叶子组织",
            description = "返回当前账号直系或继承得到的叶子节点 ID，用于大盘入口。")
    @ApiResponse(responseCode = "200", description = "叶子组织 ID 列表")
    @GetMapping("/orgs/mine")
    public ResponseEntity<List<Long>> myLeaves(HttpServletRequest request) {
        return ResponseEntity.ok(List.copyOf(membership.effectiveLeaves(RequestActor.current(request).getId())));
    }

    @Operation(operationId = "createOrg", summary = "新增组织节点",
            description = "parentId 省略表示根节点；于已有资源的叶子下新增子节点返回 422 LEAF_HAS_RESOURCES。")
    @ApiResponse(responseCode = "201", description = "已创建")
    @ApiResponse(responseCode = "422", description = "叶子已有资源：LEAF_HAS_RESOURCES")
    @PostMapping("/orgs")
    public ResponseEntity<OrgResponse> createOrg(HttpServletRequest request, @RequestBody OrgDraft draft) {
        requireAdmin(request);
        return ResponseEntity.status(201).body(response(tree.createNode(draft.getName(), draft.getParentId())));
    }

    @Operation(operationId = "renameOrg", summary = "重命名组织节点",
            description = "同级名称重复返回 409 NAME_DUPLICATED。")
    @ApiResponse(responseCode = "200", description = "已重命名")
    @ApiResponse(responseCode = "409", description = "同级重名：NAME_DUPLICATED")
    @PostMapping("/orgs/{id}/rename")
    public ResponseEntity<OrgResponse> renameOrg(HttpServletRequest request, @PathVariable long id,
                                                @RequestBody OrgDraft draft) {
        requireAdmin(request);
        return ResponseEntity.ok(response(tree.rename(id, draft.getName())));
    }

    @Operation(operationId = "addOrgMember", summary = "向组织节点添加成员",
            description = "重复添加不报错；成员资格会向下继承到叶子。")
    @ApiResponse(responseCode = "200", description = "已添加：{ ok: true }")
    @PostMapping("/orgs/{id}/members")
    public ResponseEntity<Success> addMember(HttpServletRequest request, @PathVariable long id,
                                             @RequestBody MemberDraft draft) {
        requireAdmin(request);
        membership.addMember(id, draft.getUserId());
        return ResponseEntity.ok(new Success(true));
    }

    @Operation(operationId = "removeOrgMember", summary = "移除组织节点成员",
            description = "同时撤销其由该节点继承的叶子大盘与组织告警权限。")
    @ApiResponse(responseCode = "200", description = "已移除：{ ok: true }")
    @DeleteMapping("/orgs/{id}/members/{userId}")
    public ResponseEntity<Success> removeMember(HttpServletRequest request, @PathVariable long id,
                                                @PathVariable long userId) {
        requireAdmin(request);
        membership.removeMember(id, userId);
        return ResponseEntity.ok(new Success(true));
    }

    @Operation(operationId = "previewOrgDeletion", summary = "查询删除组织节点的影响面",
            description = "仅管理员；返回将级联删除的大盘、卡片、组织告警与成员数量。")
    @ApiResponse(responseCode = "200", description = "删除预检结果")
    @GetMapping("/orgs/{id}/deletion-preview")
    public ResponseEntity<DeletionResponse> deletionPreview(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ResponseEntity.ok(OrgConvert.deletion(lifecycle.previewDeletion(id)));
    }

    @Operation(operationId = "deleteOrg", summary = "删除组织节点",
            description = "confirmName 必须与节点名完全一致；非叶子返回 409 HAS_CHILDREN；"
                    + "删除叶子会原子级联删除大盘、卡片与组织告警。")
    @ApiResponse(responseCode = "200", description = "已删除：{ ok: true }")
    @ApiResponse(responseCode = "409", description = "非叶子或确认名称不匹配：HAS_CHILDREN / CONFIRM_NAME_MISMATCH")
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
        return OrgConvert.node(node, CollectionUtils.isEmpty(nodes.childrenOf(node.getId())),
                membership.effectiveMembers(node.getId()).size());
    }
}
