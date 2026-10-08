package com.neocat.dashboard.domain.event;
import org.springframework.modulith.NamedInterface;

/**
 * 卡片变更事件发布口（PRD 05 §8）。
 *
 * <p>由 Spring Modulith 应用事件机制实现；单测使用记录替身。
 */
@FunctionalInterface
@NamedInterface("dashboard")
public interface CardEventPublisher {

    void publish(CardEvent event);

    static CardEventPublisher noop() {
        return event -> {
        };
    }
}
