package com.neocat.dashboard.api.http;

import com.neocat.dashboard.api.http.dto.*;
import com.neocat.dashboard.api.http.convert.DashboardConvert;
import com.neocat.dashboard.domain.card.CardService;
import com.neocat.dashboard.domain.dashboard.Dashboard;
import com.neocat.dashboard.domain.dashboard.DashboardService;
import com.neocat.common.http.context.RequestActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import com.neocat.dashboard.api.http.dto.CardDraft;
import com.neocat.dashboard.api.http.dto.CardResponse;
import com.neocat.dashboard.api.http.dto.DashboardDraft;
import com.neocat.dashboard.api.http.dto.DashboardResponse;
import com.neocat.dashboard.api.http.dto.SeriesResponse;
import com.neocat.dashboard.api.http.dto.Success;
import com.neocat.dashboard.api.http.dto.TargetResponse;

/**
 * 大盘 HTTP 入口；权限仍由应用服务校验，管理员无成员旁路。
 */
@Tag(name = "大盘与卡片", description = "叶子组织大盘、卡片维护与卡片序列")
@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService dashboards;

    private final CardService cards;

    private final DashboardConvert convert;

    public DashboardController(DashboardService dashboards, CardService cards, DashboardConvert convert) {
        this.dashboards = dashboards;
        this.cards = cards;
        this.convert = convert;
    }

    @Operation(operationId = "listDashboards", summary = "查询当前账号可见的大盘",
            description = "orgId 省略时返回该账号全部叶子的大盘；权限由应用服务校验，管理员无成员旁路。")
    @ApiResponse(responseCode = "200", description = "大盘列表")
    @GetMapping("/dashboards")
    public ResponseEntity<List<DashboardResponse>> list(HttpServletRequest request,
                                                        @Parameter(description = "叶子组织 ID；省略表示全部可见叶子")
                                                        @RequestParam(required = false) Long orgId) {
        RequestActor account = RequestActor.current(request);
        List<Dashboard> found = Objects.isNull(orgId) ?
                dashboards.listAll(account.getId()) : dashboards.list(account.getId(), orgId);
        return ResponseEntity.ok(found.stream().map(convert::dashboard).toList());
    }

    @Operation(operationId = "createDashboard", summary = "在叶子组织下创建大盘",
            description = "仅叶子有效成员可创建；目标组织非叶子返回 409 NOT_LEAF。")
    @ApiResponse(responseCode = "201", description = "已创建")
    @ApiResponse(responseCode = "409", description = "组织非叶子：NOT_LEAF")
    @PostMapping("/dashboards")
    public ResponseEntity<DashboardResponse> create(HttpServletRequest request, @RequestBody DashboardDraft draft) {
        return ResponseEntity.status(201).body(convert.dashboard(
                dashboards.create(RequestActor.current(request).getId(), draft.getOrgId(), draft.getName())));
    }

    @Operation(operationId = "renameDashboard", summary = "重命名大盘")
    @ApiResponse(responseCode = "200", description = "更新后的大盘")
    @PostMapping("/dashboards/{id}/rename")
    public ResponseEntity<DashboardResponse> rename(HttpServletRequest request, @PathVariable long id,
                                                    @RequestBody DashboardDraft draft) {
        return ResponseEntity.ok(convert.dashboard(dashboards.rename(RequestActor.current(request).getId(), id, draft.getName())));
    }

    @Operation(operationId = "deleteDashboard", summary = "删除大盘及其卡片",
            description = "删除后通知 alert 模块清理引用该大盘卡片的告警目标。")
    @ApiResponse(responseCode = "200", description = "已删除：{ ok: true }")
    @DeleteMapping("/dashboards/{id}")
    public ResponseEntity<Success> delete(HttpServletRequest request, @PathVariable long id) {
        dashboards.delete(RequestActor.current(request).getId(), id);
        return ResponseEntity.ok(new Success(true));
    }

    @Operation(operationId = "listCards", summary = "查询大盘下的卡片")
    @ApiResponse(responseCode = "200", description = "卡片列表")
    @GetMapping("/cards")
    public ResponseEntity<List<CardResponse>> cards(HttpServletRequest request,
            @Parameter(description = "大盘 ID", required = true) @RequestParam long dashboardId) {
        return ResponseEntity.ok(cards.cardsOf(RequestActor.current(request).getId(), dashboardId).stream()
                .map(convert::card).toList());
    }

    @Operation(operationId = "createCard", summary = "在大盘下新建卡片",
            description = "公式非法返回 400 FORMULA_INVALID；耗时与次数单位混用返回 400 UNIT_MISMATCH。")
    @ApiResponse(responseCode = "201", description = "已创建")
    @ApiResponse(responseCode = "400", description = "公式或单位非法：FORMULA_INVALID / UNIT_MISMATCH")
    @PostMapping("/dashboards/{id}/cards")
    public ResponseEntity<CardResponse> createCard(HttpServletRequest request, @PathVariable long id,
                                                   @RequestBody CardDraft draft) {
        return ResponseEntity.status(201).body(convert.card(cards.createCard(RequestActor.current(request).getId(),
                id, convert.card(draft, id))));
    }

    @Operation(operationId = "updateCard", summary = "更新卡片",
            description = "可改目标、公式、统计项、维度范围与阈值线；目标变化经事件通知 alert 更新引用。")
    @ApiResponse(responseCode = "200", description = "更新后的卡片")
    @PostMapping("/cards/{cardId}")
    public ResponseEntity<CardResponse> updateCard(HttpServletRequest request, @PathVariable long cardId,
                                                   @RequestBody CardDraft draft) {
        return ResponseEntity.ok(convert.card(cards.updateCard(RequestActor.current(request).getId(),
                cardId, convert.card(draft, 0))));
    }

    @Operation(operationId = "deleteCard", summary = "删除卡片",
            description = "删除后通知 alert 模块；引用了该卡片的告警目标会变为失效。")
    @ApiResponse(responseCode = "200", description = "已删除：{ ok: true }")
    @DeleteMapping("/cards/{cardId}")
    public ResponseEntity<Success> deleteCard(HttpServletRequest request, @PathVariable long cardId) {
        cards.deleteCard(RequestActor.current(request).getId(), cardId);
        return ResponseEntity.ok(new Success(true));
    }

    @Operation(operationId = "cardSeries", summary = "查询卡片序列",
            description = "公式结果保留 6 位小数；缺数点进入 gaps，除零点进入 isUndefined，"
                    + "两类点 value 均为 null，不改为 0。")
    @ApiResponse(responseCode = "200", description = "卡片序列，含 points、gaps 与 isUndefined")
    @GetMapping("/cards/{cardId}/series")
    public ResponseEntity<SeriesResponse> cardSeries(HttpServletRequest request,
            @Parameter(description = "卡片 ID", required = true) @PathVariable long cardId,
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "RECENT_24H") String range) {
        return ResponseEntity.ok(convert.series(cards.series(RequestActor.current(request).getId(), cardId, range)));
    }

    @Operation(operationId = "listAlertableTargets", summary = "查询组织告警可选目标",
            description = "返回该叶子大盘已引用的原始统计项与卡片结果的并集，供组织告警选目标。")
    @ApiResponse(responseCode = "200", description = "可选目标列表")
    @GetMapping("/dashboards/targets")
    public ResponseEntity<List<TargetResponse>> targets(HttpServletRequest request,
            @Parameter(description = "叶子组织 ID", required = true) @RequestParam long orgId) {
        return ResponseEntity.ok(cards.alertableTargets(RequestActor.current(request).getId(), orgId).stream()
                .map(convert::target).toList());
    }
}
