package com.neocat.catalog.domain.entry;

import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 实例目录项：服务下以上报的 instanceId 唯一，通常为 IP。
 */
@NamedInterface("catalog")
@Getter
@EqualsAndHashCode
@ToString
public class InstanceEntry {
    private final String serviceName;

    private final String instanceId;

    private final Instant firstSeenAt;

    private final Instant lastSeenAt;

    public InstanceEntry(String serviceName, String instanceId, Instant firstSeenAt, Instant lastSeenAt) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

}
