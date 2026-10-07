package com.neocat.dashboard.domain.dashboard;

import com.neocat.dashboard.domain.card.Card;

import java.util.List;
import java.util.Optional;

/**
 * 大盘与卡片仓库。
 */
@org.springframework.modulith.NamedInterface("dashboard")
public interface DashboardRepository {

    Dashboard save(Dashboard dashboard);

    @org.springframework.lang.Nullable
    Dashboard findById(long id);

    List<Dashboard> byOrg(long orgId);

    void delete(long dashboardId);

    Card saveCard(Card card);

    @org.springframework.lang.Nullable
    Card findCard(long cardId);

    List<Card> cardsOf(long dashboardId);

    void deleteCard(long cardId);
}
