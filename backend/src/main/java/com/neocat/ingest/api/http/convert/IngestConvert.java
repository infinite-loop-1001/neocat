package com.neocat.ingest.api.http.convert;

import com.neocat.ingest.api.http.dto.QueueStatsResponse;
import com.neocat.ingest.domain.receive.QueueStats;

public final class IngestConvert {
    private IngestConvert() {
    }

    public static QueueStatsResponse stats(QueueStats stats) {
        return new QueueStatsResponse(stats.getCapacity(), stats.getSize(), stats.getDroppedTotal(), stats.getWatermark());
    }
}
