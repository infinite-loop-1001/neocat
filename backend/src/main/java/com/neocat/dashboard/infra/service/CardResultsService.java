package com.neocat.dashboard.infra.service;

import com.neocat.dashboard.api.internal.CardResults;
import com.neocat.dashboard.domain.card.CardSeriesService;
import com.neocat.dashboard.domain.dashboard.DashboardRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class CardResultsService implements CardResults {
    private final DashboardRepository dashboards;

    private final CardSeriesService series;

    public CardResultsService(DashboardRepository dashboards, CardSeriesService series) {
        this.dashboards = dashboards;
        this.series = series;
    }
    @Override
    public Double value(long cardId, Instant from, Instant to) {
        var card = java.util.Optional.ofNullable(dashboards.findCard(cardId))
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));
        var points = series.series(card, from, to, 60);
        return points.size() == 1 ? points.get(0).getValue() : null;
    }
}
