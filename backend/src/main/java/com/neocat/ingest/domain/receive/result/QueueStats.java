package com.neocat.ingest.domain.receive.result;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 队列观测数据（技术方案 01 §6）。
 */
@NamedInterface("tree")
@Getter
@EqualsAndHashCode
@ToString
public class QueueStats {
    private final int size;

    private final int capacity;

    private final long droppedTotal;

    private final double watermark;

    public QueueStats(int size, int capacity, long droppedTotal, double watermark) {
        this.size = size;
        this.capacity = capacity;
        this.droppedTotal = droppedTotal;
        this.watermark = watermark;
    }

}
