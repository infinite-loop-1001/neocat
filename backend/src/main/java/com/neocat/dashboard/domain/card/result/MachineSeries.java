package com.neocat.dashboard.domain.card.result;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;
import com.neocat.dashboard.domain.card.CardPoint;

/**
 * 某台机器在卡片公式下的序列。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public class MachineSeries {
    private final String instance;

    private final List<CardPoint> points;

    public MachineSeries(String instance, List<CardPoint> points) {
        this.instance = instance;
        this.points = points;
    }
}
