package com.neocat.dashboard.domain.dashboard;

import com.neocat.dashboard.domain.card.Card;

import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.modulith.NamedInterface;

/**
 * 大盘与卡片仓库。
 */
@NamedInterface("dashboard")
public interface DashboardRepository {

    Dashboard save(Dashboard dashboard);

    @Nullable
    Dashboard findById(long id);

    List<Dashboard> byOrg(long orgId);

    void delete(long dashboardId);

    Card saveCard(Card card);

    @Nullable
    Card findCard(long cardId);

    List<Card> cardsOf(long dashboardId);

    void deleteCard(long cardId);
}
