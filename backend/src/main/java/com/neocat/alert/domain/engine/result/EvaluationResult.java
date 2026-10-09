package com.neocat.alert.domain.engine.result;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

import java.util.List;

import com.neocat.alert.domain.engine.AlertNotification;
import com.neocat.alert.domain.engine.AlertWindowState;

/**
 * 一次分钟判定的结果。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public class EvaluationResult {
    private final boolean triggered;

    private final List<AlertNotification> notifications;

    private final AlertWindowState state;

    public EvaluationResult(boolean triggered, List<AlertNotification> notifications, AlertWindowState state) {
        this.triggered = triggered;
        this.notifications = notifications;
        this.state = state;
    }

}
