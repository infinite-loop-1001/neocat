package com.neocat.ingest.infra;

import com.neocat.common.queue.BoundedDropQueue;
import com.neocat.ingest.domain.tree.MessageTree;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.context.annotation.DependsOn("ingestConfig")
public class IngestQueueConfiguration {
    @Bean
    public BoundedDropQueue<MessageTree> ingestQueue() {
        return new IngestDropQueue<>();
    }
}
