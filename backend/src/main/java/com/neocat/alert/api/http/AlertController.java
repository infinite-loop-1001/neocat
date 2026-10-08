package com.neocat.alert.api.http;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.alert.api.http.dto.AlertDtos.*;
import com.neocat.alert.api.http.convert.AlertConvert;
import com.neocat.alert.domain.rule.*;
import com.neocat.alert.domain.engine.NotificationDispatcher;
import com.neocat.alert.domain.engine.PreviewService;
import com.neocat.alert.domain.recipient.RecipientGateway;
import com.neocat.alert.domain.recipient.RecipientService;
import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.error.exception.ValidationException;
import com.neocat.common.http.context.RequestActor;
import com.neocat.alert.config.AlertConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.commons.collections4.ListUtils;

/** 告警 HTTP 入口：保存自动关闭，预览不发送、不落历史、不改变窗口。 */
@Tag(name = "告警规则", description = "告警规则维护、预览与启停用；一期无触发历史与确认")
@RestController
@DependsOn("alertConfig")
@RequestMapping("/api/alerts")
// fixme: 需要直接依赖 application 层的能力, 不能依赖直接 gateway, domain service
public class AlertController {
    private final AlertRuleRepository repository;

    private final AlertRuleService ruleService;

    private final AlertLifecycleService lifecycle;

    private final PreviewService previewService;

    private final RecipientService recipients;

    private final NotificationDispatcher dispatcher;

    private final RecipientGateway recipientGateway;

    private final AlertConvert convert;

    public AlertController(AlertRuleRepository repository, AlertRuleService ruleService,
                           AlertLifecycleService lifecycle, PreviewService previewService,
                           RecipientService recipients, NotificationDispatcher dispatcher,
                           RecipientGateway recipientGateway, AlertConvert convert) {
        // rules: controller 不能依赖 repository
        this.repository = repository;
        this.ruleService = ruleService;
        this.lifecycle = lifecycle;
        this.previewService = previewService;
        this.recipients = recipients;
        this.dispatcher = dispatcher;
        this.recipientGateway = recipientGateway;
        this.convert = convert;
    }

    @Operation(operationId = "listAlertRules", summary = "查询告警规则列表",
            description = "带 orgId 时按组织过滤并要求为该叶子有效成员；只带 scope 时按范围过滤；"
                    + "两者都不带时返回全部规则（既有契约）。列表不含任何触发历史。")
    @ApiResponse(responseCode = "200", description = "规则列表")
    @ApiResponse(responseCode = "403", description = "非该叶子成员：NOT_ORG_MEMBER")
    @GetMapping
    public ResponseEntity<List<RuleResponse>> list(HttpServletRequest request,
                                                        @Parameter(description =
                                                                "规则范围：SERVICE | ORGANIZATION；与 orgId 同时给出时以 orgId 为准")
                                                        @RequestParam(required = false) String scope,
                                                        @Parameter(description = "组织 ID，按组织范围过滤")
                                                        @RequestParam(required = false) Long orgId) {
        RequestActor account = RequestActor.current(request);
        List<AlertRule> rules;
        // rules: controller 不做逻辑处理, 只进行参数判断和向下调用和出入参数转换
        if (Objects.nonNull(orgId)) {
            // question: 这里的权限判断能不能放到权限拦截器里做 & 或者通过权限 aop 做
            //  另外就是个人权限信息能不能通过 aop 在执行请求前先把当前人的权限信息提前注入到上下文里?
            requireOrgMember(account.getId(), orgId);
            rules = repository.byOrg(orgId);
        } else if (Objects.isNull(scope)) {
            rules = repository.findAll();
        } else {
            rules = repository.findAll().stream()
                    .filter(rule -> rule.getScope().name().equalsIgnoreCase(scope)).toList();
        }
        return ResponseEntity.ok(rules.stream().map(convert::response).toList());
    }

    @Operation(operationId = "listAlertChannels", summary = "查询告警可选通知通道",
            description = "返回已知通道与可用标记；实际可投递性仍由 dispatcher.validateChannels 判断。")
    @ApiResponse(responseCode = "200", description = "通道列表")
    @GetMapping("/channels")
    // rules: List 返回值要包装成多值 response 对象, 内部还要有返回的数据量
    public ResponseEntity<List<ChannelResponse>> channels() {
        // 保留现有契约；实际可投递性仍由 dispatcher.validateChannels 判断。
        return ResponseEntity.ok(Arrays.stream(AlertChannel.values())
                .map(channel -> new ChannelResponse(channel.name(), true)).toList());
    }

    @Operation(operationId = "previewAlertRule", summary = "预览告警规则草稿",
            description = "只做试算：不发送通知、不写历史、不改变滑动窗口。"
                    + "结果取值 TRIGGER / NO_TRIGGER / INSUFFICIENT_DATA；"
                    + "缺数点 known=false；threshold 必须非空且能精确存入 DECIMAL(20,6)。")
    @ApiResponse(responseCode = "200", description = "预览结果与逐点判定")
    @ApiResponse(responseCode = "400", description = "阈值缺失或不可存储：INVALID_PARAM")
    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@RequestBody AlertDraft draft) {
        AlertRule rule = convert.rule(draft, scope(draft));
        long latestMinute = TimeProvider.delayedMinuteStart(AlertConfig.EVALUATE_DELAY_SECONDS).toEpochMilli();
        return ResponseEntity.ok(convert.preview(previewService.preview(rule, latestMinute)));
    }

    @Operation(operationId = "saveAlertRule", summary = "保存告警规则",
            description = "保存后始终 enabled=false，需手动启用；组织告警必须带 orgId 且调用者为该叶子成员；"
                    + "通道必须是平台已配置的通道，收件人与目标必须满足组织范围约束。")
    @ApiResponse(responseCode = "201", description = "已保存（enabled=false）")
    @ApiResponse(responseCode = "400", description = "组织告警缺少 orgId：ALERT_ORG_REQUIRED")
    @ApiResponse(responseCode = "403", description = "收件人/通道非法：INVALID_RECIPIENT / CHANNEL_UNAVAILABLE / NOT_ORG_MEMBER")
    @ApiResponse(responseCode = "422", description = "目标未被该叶子大盘引用：TARGET_NOT_REFERENCED")
    @PostMapping
    public ResponseEntity<RuleResponse> save(HttpServletRequest request, @RequestBody AlertDraft draft) {
        RequestActor account = RequestActor.current(request);
        AlertScope scope = scope(draft);
        if (Objects.equals(scope, AlertScope.ORGANIZATION)) {
            if (Objects.isNull(draft.getOrgId())) {
                throw new ValidationException(ErrorCode.ALERT_ORG_REQUIRED);
            }
            requireOrgMember(account.getId(), draft.getOrgId());
        }
        List<AlertChannel> channels = ListUtils.emptyIfNull(draft.getChannels()).stream().map(AlertChannel::valueOf).toList();
        AlertRule rule = convert.rule(draft, scope);
        // rules: 这类业务校验下沉到 service 处理逻辑
        dispatcher.validateChannels(channels);
        recipients.validateSelection(rule, draft.getRecipients());
        return ResponseEntity.status(201).body(convert.response(ruleService.save(rule)));
    }

    @Operation(operationId = "editAlertRule", summary = "编辑告警规则",
            description = "若原规则为启用状态，编辑会先自动关闭并清零滑动窗口，需再次手动启用；"
                    + "组织告警在带 orgId 时校验调用者成员资格。")
    @ApiResponse(responseCode = "200", description = "更新后的规则")
    @ApiResponse(responseCode = "403", description = "非该叶子成员：NOT_ORG_MEMBER")
    @PostMapping("/{id}")
    public ResponseEntity<RuleResponse> edit(
            HttpServletRequest request, @Parameter(description = "规则 ID", required = true) @PathVariable long id, @RequestBody AlertDraft draft
    ) {
        RequestActor account = RequestActor.current(request);
        AlertScope scope = scope(draft);
        if (Objects.equals(scope, AlertScope.ORGANIZATION) && Objects.nonNull(draft.getOrgId())) {
            requireOrgMember(account.getId(), draft.getOrgId());
        }
        // fixme: 这里要由 AlterRuleService 处理
        return ResponseEntity.ok(convert.response(lifecycle.edit(id, convert.rule(draft, scope))));
    }

    // rules: 这里指返回是否成功就可以了, 不需要返回全量数据
    @Operation(operationId = "enableAlertRule", summary = "手动启用告警规则",
            description = "窗口从零开始重新累计，不沿用关闭前的判定点。")
    @ApiResponse(responseCode = "200", description = "启用后的规则")
    @PostMapping("/{id}/enable")
    public ResponseEntity<RuleResponse> enable(@Parameter(description = "规则 ID", required = true) @PathVariable long id) {
        // fixme: 这里要由 AlterRuleService 处理
        return ResponseEntity.ok(convert.response(lifecycle.enable(id, TimeProvider.millis())));
    }

    @Operation(operationId = "disableAlertRule", summary = "手动关闭告警规则",
            description = "关闭后不再参与判定；重新启用时窗口从零开始。")
    @ApiResponse(responseCode = "200", description = "关闭后的规则")
    @PostMapping("/{id}/disable")
    public ResponseEntity<RuleResponse> disable(@Parameter(description = "规则 ID", required = true) @PathVariable long id) {
        // fixme: 这里要由 AlterRuleService 处理
        return ResponseEntity.ok(convert.response(lifecycle.disable(id)));
    }

    @Operation(operationId = "deleteAlertRule", summary = "删除告警规则",
            description = "删除规则及其条件、收件人与滑动窗口点；一期不存在触发历史可删除。")
    @ApiResponse(responseCode = "200", description = "已删除：{ ok: true }")
    @DeleteMapping("/{id}")
    public ResponseEntity<Success> delete(@Parameter(description = "规则 ID", required = true) @PathVariable long id) {
        // fixme: 这里要由 AlterRuleService 处理
        lifecycle.delete(id);
        return ResponseEntity.ok(new Success(true));
    }

    private AlertScope scope(AlertDraft draft) {
        return Objects.isNull(draft.getScope()) ? AlertScope.SERVICE : AlertScope.valueOf(draft.getScope());
    }

    private void requireOrgMember(long accountId, long orgId) {
        if (!recipientGateway.isEffectiveMember(accountId, orgId)) {
            throw new AuthorizationException(ErrorCode.NOT_ORG_MEMBER);
        }
    }
}
