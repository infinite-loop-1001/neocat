package com.neocat.ingest.domain.tree;

/**
 * 跨服务调用载荷（PRD 04 §6）。被调用方未上报时，依赖边仍由调用方观测产生。
 */
@org.springframework.modulith.NamedInterface("tree")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
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

