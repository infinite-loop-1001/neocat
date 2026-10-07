package com.neocat.catalog.domain.entry;

import java.time.Instant;

/**
 * 服务目录项（PRD 00 §4.1）：以上报的 serviceName 为唯一标识，同名即同一服务。
 * 首次合法上报后自动出现，平台不提供手工建服务。
 */
@org.springframework.modulith.NamedInterface("catalog")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class ServiceEntry {
    private final String name;

    private final Instant firstSeenAt;

    private final Instant lastSeenAt;

    public ServiceEntry(String name, Instant firstSeenAt, Instant lastSeenAt) {
        this.name = name;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

}
