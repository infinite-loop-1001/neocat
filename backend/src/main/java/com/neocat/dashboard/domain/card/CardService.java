package com.neocat.dashboard.domain.card;

import com.neocat.common.time.clock.TimeProvider;

import com.google.common.collect.Lists;
import com.neocat.dashboard.domain.access.OrgAccessGateway;
import com.neocat.dashboard.domain.dashboard.Dashboard;
import com.neocat.dashboard.domain.dashboard.DashboardRepository;
import com.neocat.dashboard.domain.event.CardEventPublisher;
import com.neocat.dashboard.domain.formula.FormulaParser;
import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.error.exception.BusinessRuleException;
import com.neocat.common.error.exception.ResourceNotFoundException;
import com.neocat.common.error.exception.ValidationException;
import com.neocat.query.domain.stat.Stat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import static com.neocat.common.error.ErrorCode.CARD_EVALUATION_UNAVAILABLE;
import static com.neocat.common.error.ErrorCode.CARD_NOT_FOUND;
import static com.neocat.common.error.ErrorCode.CARD_NOT_IN_DASHBOARD;
import static com.neocat.common.error.ErrorCode.CARD_TARGET_REQUIRED;
import static com.neocat.common.error.ErrorCode.DASHBOARD_NOT_FOUND;
import static com.neocat.common.error.ErrorCode.FORMULA_INVALID;
import static com.neocat.common.error.ErrorCode.NOT_ORG_MEMBER;
import static com.neocat.common.error.ErrorCode.UNIT_MISMATCH;
import org.apache.commons.collections4.CollectionUtils;
import com.neocat.common.locking.MySqlLocked;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.neocat.dashboard.domain.event.CardDeleted;
import com.neocat.dashboard.domain.event.CardTargetChanged;
import com.neocat.dashboard.domain.formula.ParseOutcome;

/**
 * 卡片用例（PRD 05 §2–§9）。
 *
 * <p>与告警的联动契约（PRD 05 §8）由两类事件承载：
 * <ul>
 *   <li>{@link com.neocat.dashboard.domain.event.CardTargetChanged}：**仅当公式或目标变化时**发布。
 *       这样「只改时间范围/顺序」不会无谓地把已启用的规则关掉；</li>
 *   <li>{@link com.neocat.dashboard.domain.event.CardDeleted}：删除卡片时发布，附带受影响统计项，
 *       让 alert 能按「是否仍被其他卡片引用」决定失效与否。</li>
 * </ul>
 *
 * <p>组织告警可选目标（PRD 05 §9）由 {@link #alertableTargets} 计算：
 * 原始统计项按 (服务, 指标对象, 统计项) 去重，卡片结果按其 identity 去重。
 * {@link #stillReferenced} **只统计指定叶子的大盘**，其他叶子对同一目标的引用不算数。
 */
@Service
@NamedInterface("dashboard")
public class CardService {

    private final DashboardRepository dashboards;

    private final OrgAccessGateway orgAccess;

    private final CardEventPublisher events;

    private final CardEvaluator evaluator;

    private final CardSeriesService seriesService;

    public CardService(DashboardRepository dashboards, OrgAccessGateway orgAccess,
                       CardEventPublisher events) {
        this(dashboards, orgAccess, events, null);
    }
    /**
     * @param seriesService 卡片序列组装器；为 null 表示该部署未启用卡片求值
     *                      （此时 {@link #series} 会明确报错而非返回空数据）
     */
    @Autowired
    public CardService(DashboardRepository dashboards, OrgAccessGateway orgAccess,
                       CardEventPublisher events, CardSeriesService seriesService) {
        this.dashboards = dashboards;
        this.orgAccess = orgAccess;
        this.events = events;
        this.seriesService = seriesService;
        this.evaluator = new CardEvaluator();
    }
    /** 新建卡片：校验目标与公式单位。 */
    @MySqlLocked("metadata")
    public Card createCard(long accountId, long dashboardId, Card draft) {
        Dashboard dashboard = requireDashboard(accountId, dashboardId);
        validate(draft);
        Card toSave = Card.withoutThresholds(0, dashboard.getId(), draft.getService(), draft.getTargetKind(), draft.getTargetType(),
                draft.getTargetName(), draft.getMetricLabels(), draft.getInstanceScope(), draft.getFormula(),
                draft.getTimeRange(), draft.getOrderNo());
        return dashboards.saveCard(toSave);
    }
    /**
     * 更新卡片。
     *
     * <p>仅当**目标或公式**发生变化时发布 {@link com.neocat.dashboard.domain.event.CardTargetChanged}：
     * 该事件会让关联的组织告警跟随新公式、保存为关闭并清零窗口。
     */
    @Transactional
    @MySqlLocked("metadata")
    public Card updateCard(long accountId, long cardId, Card draft) {
        Card existing = requireCard(accountId, cardId);
        validate(draft);

        Card updated = Card.withoutThresholds(existing.getId(), existing.getDashboardId(), draft.getService(), draft.getTargetKind(),
                draft.getTargetType(), draft.getTargetName(), draft.getMetricLabels(), draft.getInstanceScope(),
                draft.getFormula(), draft.getTimeRange(), draft.getOrderNo());
        Card saved = dashboards.saveCard(updated);

        if (targetOrFormulaChanged(existing, saved)) {
            events.publish(new CardTargetChanged(
                    saved.getId(), saved.getDashboardId(), orgIdOf(saved),
                    saved.getService(), saved.getTargetKind(), saved.getTargetType(), saved.getTargetName(),
                    saved.getMetricLabels(), saved.getFormula(), statNames(saved.getFormula())));
        }
        return saved;
    }
    /** 删除卡片：发布 {@link com.neocat.dashboard.domain.event.CardDeleted}。 */
    @Transactional
    @MySqlLocked("metadata")
    public void deleteCard(long accountId, long cardId) {
        Card existing = requireCard(accountId, cardId);
        dashboards.deleteCard(cardId);
        events.publish(new CardDeleted(
                existing.getId(), existing.getDashboardId(), orgIdOf(existing),
                existing.targetIdentity(), statNames(existing.getFormula())));
    }
    /** 调整卡片顺序：不发布任何事件，因而不影响任何规则。 */
    @MySqlLocked("metadata")
    public void reorder(long accountId, long dashboardId, List<Long> cardIds) {
        Dashboard dashboard = requireDashboard(accountId, dashboardId);
        if (CollectionUtils.isEmpty(cardIds)) {
            return;
        }
        int order = 0;
        for (Long cardId : cardIds) {
            Card card = Optional.ofNullable(dashboards.findCard(cardId))
                    .orElseThrow(() -> new ResourceNotFoundException(CARD_NOT_FOUND, cardId));
            if (card.getDashboardId() != dashboard.getId()) {
                throw new ValidationException(CARD_NOT_IN_DASHBOARD);
            }
            dashboards.saveCard(Card.withoutThresholds(card.getId(), card.getDashboardId(), card.getService(), card.getTargetKind(),
                    card.getTargetType(), card.getTargetName(), card.getMetricLabels(), card.getInstanceScope(),
                    card.getFormula(), card.getTimeRange(), order++));
        }
    }
    /**
     * 组织的卡片列表（需成员资格）。
     */
    public List<Card> cardsOf(long accountId, long dashboardId) {
        Dashboard dashboard = Optional.ofNullable(dashboards.findById(dashboardId))
                .orElseThrow(() -> new ResourceNotFoundException(DASHBOARD_NOT_FOUND, dashboardId));
        requireMember(accountId, dashboard.getOrgId());
        return dashboards.cardsOf(dashboardId);
    }
    /**
     * 卡片序列（PRD 05 §5）：缺数入缺、除零不可计算。
     *
     * <p>要求成员资格；非成员按不可见处理。
     */
    public Map<String, Object> series(long accountId, long cardId, String range) {
        Card card = Optional.ofNullable(dashboards.findCard(cardId))
                .orElseThrow(() -> new ResourceNotFoundException(CARD_NOT_FOUND, cardId));
        Dashboard dashboard = Optional.ofNullable(dashboards.findById(card.getDashboardId()))
                .orElseThrow(() -> new ResourceNotFoundException(DASHBOARD_NOT_FOUND, card.getDashboardId()));
        requireMember(accountId, dashboard.getOrgId());
        if (Objects.isNull(seriesService)) {
            throw new BusinessRuleException(CARD_EVALUATION_UNAVAILABLE);
        }

        long bucketSeconds = bucketSecondsOf(range);
        Instant to = TimeProvider.now();
        Instant from = to.minusSeconds(bucketSeconds * 12L);
        List<CardPoint> points = seriesService.series(card, from, to, bucketSeconds);

        ParseOutcome parsed = new FormulaParser().parse(card.getFormula());
        String unit = parsed.valid() ? parsed.getFormula().unit().name() : "NUMBER";
        return seriesService.describe(card, points, unit);
    }
    /** 时间范围的默认粒度（与 PRD 03 §2.2 的快捷范围一致）。 */
    private long bucketSecondsOf(String range) {
        return switch (Objects.isNull(range) ? "RECENT_24H" : range.toUpperCase(Locale.ROOT)) {
            case "RECENT_1H" -> 60L;
            case "RECENT_3H" -> 300L;
            case "RECENT_6H" -> 600L;
            case "RECENT_12H" -> 1200L;
            case "TODAY" -> 600L;
            case "THIS_WEEK" -> 3600L;
            default -> 3600L;
        };
    }
    /**
     * 组织告警可选目标并集（PRD 05 §9）：
     * 该叶子全部大盘已引用的**原始统计项** ∪ 卡片**计算结果**。
     */
    public List<AlertableTarget> alertableTargets(long accountId, long orgId) {
        if (!orgAccess.isEffectiveMember(accountId, orgId)) {
            throw new AuthorizationException(NOT_ORG_MEMBER);
        }
        List<AlertableTarget> targets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> rawSeen = new LinkedHashSet<>();

        for (Dashboard dashboard : dashboards.byOrg(orgId)) {
            for (Card card : dashboards.cardsOf(dashboard.getId())) {
                // 卡片结果目标
                AlertableTarget cardResult = new AlertableTarget(AlertableTargetKind.CARD_RESULT,
                        card.getId(), card.getService(), card.getTargetKind(), card.getTargetType(), card.getTargetName(),
                        card.getMetricLabels(), statsOf(card.getFormula()));
                if (seen.add(cardResult.identity() + "|" + card.getId())) {
                    targets.add(cardResult);
                }
                // 原始统计项目标：按 (服务, 指标对象, 统计项) 去重
                for (Stat stat : statsOf(card.getFormula())) {
                    String rawKey = rawKeyOf(card, stat);
                    if (rawSeen.add(rawKey)) {
                        targets.add(new AlertableTarget(AlertableTargetKind.RAW_STAT,
                                0, card.getService(), card.getTargetKind(), card.getTargetType(), card.getTargetName(),
                                card.getMetricLabels(), List.of(stat)));
                    }
                }
            }
        }
        return targets;
    }
    /**
     * 某目标是否仍被**该叶子**的任意卡片引用（PRD 05 §9）。
     *
     * <p>只检查该叶子的大盘；其他叶子对同一目标的引用不使本叶子的规则继续有效。
     */
    public boolean stillReferenced(long orgId, AlertableTarget target) {
        if (Objects.isNull(target)) {
            return false;
        }
        for (Dashboard dashboard : dashboards.byOrg(orgId)) {
            for (Card card : dashboards.cardsOf(dashboard.getId())) {
                if (Objects.equals(target.getKind(), AlertableTargetKind.CARD_RESULT)) {
                    if (card.getId() == target.getCardId()) {
                        return true;
                    }
                    continue;
                }
                for (Stat stat : target.getStats()) {
                    if (rawKeyOf(card, stat).startsWith(rawPrefixOf(target))
                            && statsOf(card.getFormula()).contains(stat)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ── 内部 ─────────────────────────────────────────────────

    private void validate(Card draft) {
        String targetError = evaluator.validateTarget(draft);
        if (Objects.equals(CardEvaluator.INVALID_TARGET, targetError)) {
            throw new ValidationException(CARD_TARGET_REQUIRED);
        }
        if (Objects.equals(UNIT_MISMATCH.name(), targetError)) {
            throw new ValidationException(UNIT_MISMATCH);
        }
        if (Objects.nonNull(targetError)) {
            throw new ValidationException(FORMULA_INVALID, targetError);
        }
    }
    /** 要求调用者是该组织的有效成员；管理员没有旁路（PRD 05 §1）。 */
    private void requireMember(long accountId, long orgId) {
        if (!orgAccess.isEffectiveMember(accountId, orgId)) {
            throw new AuthorizationException(NOT_ORG_MEMBER);
        }
    }
    private Dashboard requireDashboard(long accountId, long dashboardId) {
        Dashboard dashboard = Optional.ofNullable(dashboards.findById(dashboardId))
                .orElseThrow(() -> new ResourceNotFoundException(DASHBOARD_NOT_FOUND, dashboardId));
        if (!orgAccess.isEffectiveMember(accountId, dashboard.getOrgId())) {
            throw new AuthorizationException(NOT_ORG_MEMBER);
        }
        return dashboard;
    }
    private Card requireCard(long accountId, long cardId) {
        Card card = Optional.ofNullable(dashboards.findCard(cardId))
                .orElseThrow(() -> new ResourceNotFoundException(CARD_NOT_FOUND, cardId));
        Dashboard dashboard = Optional.ofNullable(dashboards.findById(card.getDashboardId()))
                .orElseThrow(() -> new ResourceNotFoundException(DASHBOARD_NOT_FOUND, card.getDashboardId()));
        if (!orgAccess.isEffectiveMember(accountId, dashboard.getOrgId())) {
            throw new AuthorizationException(NOT_ORG_MEMBER);
        }
        return card;
    }
    private long orgIdOf(Card card) {
        return Optional.ofNullable(dashboards.findById(card.getDashboardId())).map(Dashboard::getOrgId).orElse(0L);
    }
    private boolean targetOrFormulaChanged(Card before, Card after) {
        return !Objects.equals(normalize(before.getFormula()), normalize(after.getFormula()))
                || !Objects.equals(before.targetIdentity(), after.targetIdentity());
    }
    private String normalize(String formula) {
        return Objects.isNull(formula) ? "" : formula.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
    private List<String> statNames(String formula) {
        return statsOf(formula).stream().map(Enum::name).toList();
    }
    private List<Stat> statsOf(String formula) {
        if (Objects.isNull(formula)) {
            return Lists.newArrayList();
        }
        ParseOutcome parsed = new FormulaParser().parse(formula);
        if (!parsed.valid()) {
            return Lists.newArrayList();
        }
        Map<Stat, Boolean> unique = new LinkedHashMap<>();
        parsed.getFormula().referencedStats().forEach(stat -> unique.put(stat, true));
        return List.copyOf(unique.keySet());
    }
    private String rawKeyOf(Card card, Stat stat) {
        return rawPrefixOf(card) + "|" + stat.name();
    }
    private String rawPrefixOf(Card card) {
        return card.targetIdentity();
    }
    private String rawPrefixOf(AlertableTarget target) {
        return Objects.equals(target.getKind(), AlertableTargetKind.RAW_STAT)
                ? target.getService() + "|" + target.getTargetKind() + "|"
                  + (Objects.isNull(target.getTargetType()) ? "" : target.getTargetType()) + "|"
                  + (Objects.isNull(target.getTargetName()) ? "" : target.getTargetName()) + "|"
                  + (Objects.isNull(target.getMetricLabels()) ? "" : target.getMetricLabels())
                : "";
    }
}
