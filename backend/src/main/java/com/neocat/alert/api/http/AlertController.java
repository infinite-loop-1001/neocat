package com.neocat.alert.api.http;

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
import com.neocat.common.config.AlertConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.commons.collections4.ListUtils;

/** 告警 HTTP 入口：保存自动关闭，预览不发送、不落历史、不改变窗口。 */
// rules: 使用 springDoc 添加接口文档
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

    private final Clock clock;

    private final AlertConvert convert;

    public AlertController(AlertRuleRepository repository, AlertRuleService ruleService,
                           AlertLifecycleService lifecycle, PreviewService previewService,
                           RecipientService recipients, NotificationDispatcher dispatcher,
                           RecipientGateway recipientGateway, Clock clock, AlertConvert convert) {
        // rules: controller 不能依赖 repository
        this.repository = repository;
        this.ruleService = ruleService;
        this.lifecycle = lifecycle;
        this.previewService = previewService;
        this.recipients = recipients;
        this.dispatcher = dispatcher;
        this.recipientGateway = recipientGateway;
        this.clock = clock;
        this.convert = convert;
    }

    @GetMapping
    public ResponseEntity<List<RuleResponse>> list(HttpServletRequest request,
                                                   @RequestParam(required = false) String scope,
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

    @GetMapping("/channels")
    // rules: List 返回值要包装成多值 response 对象, 内部还要有返回的数据量
    public ResponseEntity<List<ChannelResponse>> channels() {
        // 保留现有契约；实际可投递性仍由 dispatcher.validateChannels 判断。
        return ResponseEntity.ok(Arrays.stream(AlertChannel.values())
                .map(channel -> new ChannelResponse(channel.name(), true)).toList());
    }

    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(@RequestBody AlertDraft draft) {
        AlertRule rule = convert.rule(draft, scope(draft));
        long latestMinute = clock.instant().minusSeconds(AlertConfig.EVALUATE_DELAY_SECONDS).toEpochMilli()
                / 60_000L * 60_000L;
        return ResponseEntity.ok(convert.preview(previewService.preview(rule, latestMinute)));
    }

    @PostMapping
    public ResponseEntity<RuleResponse> save(HttpServletRequest request, @RequestBody AlertDraft draft) {
        RequestActor account = RequestActor.current(request);
        AlertScope scope = scope(draft);
        if (scope == AlertScope.ORGANIZATION) {
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

    @PostMapping("/{id}")
    public ResponseEntity<RuleResponse> edit(
            HttpServletRequest request, @PathVariable long id, @RequestBody AlertDraft draft
    ) {
        RequestActor account = RequestActor.current(request);
        AlertScope scope = scope(draft);
        if (scope == AlertScope.ORGANIZATION && Objects.nonNull(draft.getOrgId())) {
            requireOrgMember(account.getId(), draft.getOrgId());
        }
        // fixme: 这里要由 AlterRuleService 处理
        return ResponseEntity.ok(convert.response(lifecycle.edit(id, convert.rule(draft, scope))));
    }

    @PostMapping("/{id}/enable")
    // fixme: 这里指返回是否成功就可以了, 不需要返回全量数据
    public ResponseEntity<RuleResponse> enable(@PathVariable long id) {
        // fixme: 这里要由 AlterRuleService 处理
        return ResponseEntity.ok(convert.response(lifecycle.enable(id, clock.millis())));
    }

    @PostMapping("/{id}/disable")
    // fixme: 这里指返回是否成功就可以了, 不需要返回全量数据
    public ResponseEntity<RuleResponse> disable(@PathVariable long id) {
        // fixme: 这里要由 AlterRuleService 处理
        return ResponseEntity.ok(convert.response(lifecycle.disable(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Success> delete(@PathVariable long id) {
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
