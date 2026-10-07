package com.neocat.dashboard.infra.listener;

import com.neocat.dashboard.domain.event.CardEvent;
import com.neocat.dashboard.domain.event.CardEventPublisher;
import com.neocat.dashboard.api.internal.CardChange;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class SpringCardEventPublisher implements CardEventPublisher {
    private final ApplicationEventPublisher publisher;

    public SpringCardEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }
    @Override
    public void publish(CardEvent event) {
        publisher.publishEvent(event);
        if (event instanceof CardEvent.CardTargetChanged changed) {
            publisher.publishEvent(new CardChange(changed.getCardId(), changed.getOrgId(), changed.getService(),
                    changed.getTargetKind(), changed.getTargetType(), changed.getTargetName(), changed.getMetricLabels(),
                    changed.getNewFormula(), changed.getAffectedStats(), false));
        } else if (event instanceof CardEvent.CardDeleted deleted) {
            // The removed target identity is the same five-part identity used by Card.targetIdentity().
            String[] parts = deleted.getRemovedTargetIdentity().split("\\|", -1);
            if (parts.length != 5) {
                throw new IllegalArgumentException("Invalid removed card target identity");
            }
            publisher.publishEvent(new CardChange(deleted.getCardId(), deleted.getOrgId(), parts[0], parts[1],
                    parts[2], parts[3], parts[4], null, deleted.getAffectedStats(), true));
        }
    }
}
