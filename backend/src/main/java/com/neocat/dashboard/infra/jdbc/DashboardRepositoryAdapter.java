package com.neocat.dashboard.infra.jdbc;

import com.google.common.collect.Lists;
import com.neocat.dashboard.domain.card.Card;
import com.neocat.dashboard.domain.dashboard.Dashboard;
import com.neocat.dashboard.domain.dashboard.DashboardRepository;
import com.neocat.dashboard.domain.formula.FormulaParser;
import com.neocat.dashboard.domain.card.ThresholdLine;
import com.neocat.dashboard.domain.card.ThresholdDirection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocat.organization.api.internal.OrgResourceIndex;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Objects;

import org.apache.commons.collections4.ListUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import com.neocat.dashboard.infra.jdbc.row.CardRow;
import com.neocat.dashboard.infra.jdbc.row.DashboardRow;

/**
 * 大盘与卡片的 MyBatis 适配器（表 {@code nc_dashboard} / {@code nc_card} / {@code nc_card_threshold_line}）。
 *
 * <p>卡片目标字段（target_kind/type/name、metric_labels）与公式一起持久化；
 * {@code formula_unit} 保存保存期校验出的单位，避免查询期重新解析。
 */
@Repository
public class DashboardRepositoryAdapter implements DashboardRepository {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DashboardMapper mapper;

    private final OrgResourceIndex resources;

    public DashboardRepositoryAdapter(DashboardMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public DashboardRepositoryAdapter(DashboardMapper mapper, OrgResourceIndex resources) {
        this.mapper = mapper;
        this.resources = resources;
    }

    // ── 大盘 ─────────────────────────────────────────────────

    @Override
    @Transactional
    public Dashboard save(Dashboard dashboard) {
        DashboardRow row = new DashboardRow();
        row.setId(dashboard.getId() == 0 ? null : dashboard.getId());
        row.setOrgId(dashboard.getOrgId());
        row.setName(dashboard.getName());
        row.setOrderNo(dashboard.getOrderNo());
        if (Objects.isNull(row.getId())) {
            mapper.insertDashboard(row);
        } else {
            mapper.updateDashboard(row);
        }
        Dashboard saved = new Dashboard(row.getId(), row.getOrgId(), row.getName(), row.getOrderNo());
        if (Objects.nonNull(resources)) {
            resources.dashboard(saved.getOrgId(), saved.getId(), saved.getName());
        }
        return saved;
    }

    @Override
    public Dashboard findById(long id) {
        var row = mapper.selectDashboard(id);
        return Objects.isNull(row) ? null : toDashboard(row);
    }

    @Override
    public List<Dashboard> byOrg(long orgId) {
        return mapper.selectDashboardsByOrg(orgId).stream()
                .map(DashboardRepositoryAdapter::toDashboard)
                .toList();
    }

    @Override
    @Transactional
    public void delete(long dashboardId) {
        Dashboard existing = Optional.ofNullable(findById(dashboardId)).orElseThrow();
        // 表定义无外键，级联必须在同一事务内按依赖顺序显式删除：
        // 阈值线 → 卡片 → 大盘，避免留下悬挂引用。
        for (CardRow card : mapper.selectCardsByDashboard(dashboardId)) {
            mapper.deleteThresholdLines(card.getId());
            mapper.deleteCard(card.getId());
        }
        mapper.deleteDashboard(dashboardId);
        if (Objects.nonNull(resources)) {
            resources.removeDashboard(existing.getOrgId(), dashboardId);
        }
    }

    // ── 卡片 ─────────────────────────────────────────────────

    @Override
    @Transactional
    public Card saveCard(Card card) {
        CardRow row = new CardRow();
        row.setId(card.getId() == 0 ? null : card.getId());
        row.setDashboardId(card.getDashboardId());
        row.setService(card.getService());
        row.setTargetKind(card.getTargetKind());
        row.setTargetType(card.getTargetType());
        row.setTargetName(card.getTargetName());
        row.setMetricName(Objects.equals("METRIC", card.getTargetKind()) ? card.getTargetType() : null);
        row.setMetricLabels(toJson(card.getMetricLabels()));
        row.setInstanceScope(toJson(ListUtils.emptyIfNull(card.getInstanceScope())));
        row.setFormula(card.getFormula());
        var parsed = new FormulaParser().parse(card.getFormula());
        if (!parsed.valid()) {
            throw new IllegalArgumentException("Invalid card formula: " + card.getFormula());
        }
        row.setFormulaUnit(parsed.getFormula().unit().name());
        row.setTimeRange(card.getTimeRange());
        row.setOrderNo(card.getOrderNo());
        if (Objects.isNull(row.getId())) {
            mapper.insertCard(row);
        } else {
            mapper.updateCard(row);
        }
        Card saved = new Card(row.getId(), card.getDashboardId(), card.getService(), card.getTargetKind(),
                card.getTargetType(), card.getTargetName(), card.getMetricLabels(), card.getInstanceScope(),
                card.getFormula(), card.getTimeRange(), card.getOrderNo(), card.getThresholdLines());
        mapper.deleteThresholdLines(saved.getId());
        if (Objects.nonNull(saved.getThresholdLines())) {
            for (ThresholdLine line : saved.getThresholdLines()) {
                mapper.insertThresholdLine(saved.getId(), line.getDirection().name(), line.getValue());
            }
        }
        updateCardCount(card.getDashboardId());
        return saved;
    }

    @Override
    public Card findCard(long cardId) {
        var row = mapper.selectCard(cardId);
        return Objects.isNull(row) ? null : toCard(row);
    }

    @Override
    public List<Card> cardsOf(long dashboardId) {
        return mapper.selectCardsByDashboard(dashboardId).stream()
                .map(this::toCard)
                .toList();
    }

    @Override
    @Transactional
    public void deleteCard(long cardId) {
        Card existing = Optional.ofNullable(findCard(cardId)).orElseThrow();
        // 无外键级联：先删阈值线，避免留下悬挂行
        mapper.deleteThresholdLines(cardId);
        mapper.deleteCard(cardId);
        updateCardCount(existing.getDashboardId());
    }

    private void updateCardCount(long dashboardId) {
        if (Objects.nonNull(resources)) {
            Dashboard dashboard = Optional.ofNullable(findById(dashboardId)).orElseThrow();
            resources.cardCount(dashboard.getOrgId(), dashboardId, mapper.countCards(dashboardId));
        }
    }

    // ── 转换 ─────────────────────────────────────────────────

    private static Dashboard toDashboard(DashboardRow row) {
        return new Dashboard(row.getId(), row.getOrgId(), row.getName(), row.getOrderNo());
    }

    private Card toCard(CardRow row) {
        return new Card(row.getId(), row.getDashboardId(), row.getService(), row.getTargetKind(),
                row.getTargetType(), row.getTargetName(), fromJson(row.getMetricLabels(), String.class),
                Objects.isNull(row.getInstanceScope()) ? Lists.newArrayList() :
                        fromJson(row.getInstanceScope(), new TypeReference<>() {
                        }),
                row.getFormula(), row.getTimeRange(), row.getOrderNo(),
                mapper.selectThresholdLines(row.getId()).stream()
                        .map(line -> new ThresholdLine(ThresholdDirection.valueOf(line.getDirection()), line.getValue()))
                        .toList());
    }

    private static String toJson(Object value) {
        if (Objects.isNull(value)) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid card value", e);
        }
    }

    private static <T> T fromJson(String value, Class<T> type) {
        if (Objects.isNull(value)) return null;
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored card value", e);
        }
    }

    private static <T> T fromJson(String value, TypeReference<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored card value", e);
        }
    }

}