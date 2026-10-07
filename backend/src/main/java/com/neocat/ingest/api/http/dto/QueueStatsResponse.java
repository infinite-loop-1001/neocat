package com.neocat.ingest.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QueueStatsResponse {
    private final int capacity;

    private final int size;

    private final long droppedTotal;

    private final double watermark;
}
