package com.neocat.dashboard.api.http;

import com.neocat.dashboard.api.http.dto.DashboardDtos.*;
import com.neocat.dashboard.api.http.convert.DashboardConvert;
import com.neocat.dashboard.domain.card.CardService;
import com.neocat.dashboard.domain.dashboard.Dashboard;
import com.neocat.dashboard.domain.dashboard.DashboardService;
import com.neocat.common.http.context.RequestActor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 大盘 HTTP 入口；权限仍由应用服务校验，管理员无成员旁路。 */
@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService dashboards;

    private final CardService cards;

    public DashboardController(DashboardService dashboards, CardService cards) {
        this.dashboards = dashboards;
        this.cards = cards;
    }

    @GetMapping("/dashboards")
    public ResponseEntity<List<DashboardResponse>> list(HttpServletRequest request,
                                                         @RequestParam(required = false) Long orgId) {
        RequestActor account = RequestActor.current(request);
        List<Dashboard> found = orgId == null ? dashboards.listAll(account.getId()) : dashboards.list(account.getId(), orgId);
        return ResponseEntity.ok(found.stream().map(DashboardConvert::dashboard).toList());
    }

    @PostMapping("/dashboards")
    public ResponseEntity<DashboardResponse> create(HttpServletRequest request, @RequestBody DashboardDraft draft) {
        return ResponseEntity.status(201).body(DashboardConvert.dashboard(
                dashboards.create(RequestActor.current(request).getId(), draft.getOrgId(), draft.getName())));
    }

    @PostMapping("/dashboards/{id}/rename")
    public ResponseEntity<DashboardResponse> rename(HttpServletRequest request, @PathVariable long id,
                                                    @RequestBody DashboardDraft draft) {
        return ResponseEntity.ok(DashboardConvert.dashboard(dashboards.rename(RequestActor.current(request).getId(), id, draft.getName())));
    }

    @DeleteMapping("/dashboards/{id}")
    public ResponseEntity<Success> delete(HttpServletRequest request, @PathVariable long id) {
        dashboards.delete(RequestActor.current(request).getId(), id);
        return ResponseEntity.ok(new Success(true));
    }

    @GetMapping("/cards")
    public ResponseEntity<List<CardResponse>> cards(HttpServletRequest request, @RequestParam long dashboardId) {
        return ResponseEntity.ok(cards.cardsOf(RequestActor.current(request).getId(), dashboardId).stream()
                .map(DashboardConvert::card).toList());
    }

    @PostMapping("/dashboards/{id}/cards")
    public ResponseEntity<CardResponse> createCard(HttpServletRequest request, @PathVariable long id,
                                                   @RequestBody CardDraft draft) {
        return ResponseEntity.status(201).body(DashboardConvert.card(cards.createCard(RequestActor.current(request).getId(),
                id, DashboardConvert.card(draft, id))));
    }

    @PostMapping("/cards/{cardId}")
    public ResponseEntity<CardResponse> updateCard(HttpServletRequest request, @PathVariable long cardId,
                                                   @RequestBody CardDraft draft) {
        return ResponseEntity.ok(DashboardConvert.card(cards.updateCard(RequestActor.current(request).getId(),
                cardId, DashboardConvert.card(draft, 0))));
    }

    @DeleteMapping("/cards/{cardId}")
    public ResponseEntity<Success> deleteCard(HttpServletRequest request, @PathVariable long cardId) {
        cards.deleteCard(RequestActor.current(request).getId(), cardId);
        return ResponseEntity.ok(new Success(true));
    }

    @GetMapping("/cards/{cardId}/series")
    public ResponseEntity<SeriesResponse> cardSeries(HttpServletRequest request, @PathVariable long cardId,
                                                     @RequestParam(defaultValue = "RECENT_24H") String range) {
        return ResponseEntity.ok(DashboardConvert.series(cards.series(RequestActor.current(request).getId(), cardId, range)));
    }

    @GetMapping("/dashboards/targets")
    public ResponseEntity<List<TargetResponse>> targets(HttpServletRequest request, @RequestParam long orgId) {
        return ResponseEntity.ok(cards.alertableTargets(RequestActor.current(request).getId(), orgId).stream()
                .map(DashboardConvert::target).toList());
    }
}
