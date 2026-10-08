package com.neocat.ingest.domain.tree;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 跨服务调用载荷（PRD 04 §6）。被调用方未上报时，依赖边仍由调用方观测产生。
 */
@NamedInterface("tree")
@Getter
@EqualsAndHashCode
@ToString
public class RemoteCallValue {
    private final String downstreamService;

    private final String downstreamAddress;

    private final String callType;

    private final String status;

    public RemoteCallValue(String downstreamService, String downstreamAddress, String callType, String status) {
        this.downstreamService = downstreamService;
        this.downstreamAddress = downstreamAddress;
        this.callType = callType;
        this.status = status;
    }

}
