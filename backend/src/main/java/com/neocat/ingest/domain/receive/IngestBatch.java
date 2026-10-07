package com.neocat.ingest.domain.receive;

import com.neocat.ingest.domain.tree.MessageTree;

import java.util.List;

/**
 * 一次上报批次（技术方案 04 §2）。
 */
@org.springframework.modulith.NamedInterface("tree")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class IngestBatch {
    private final String protocolVersion;

    private final List<MessageTree> trees;

    public IngestBatch(String protocolVersion, List<MessageTree> trees) {
        this.protocolVersion = protocolVersion;
        this.trees = trees;
    }

    public static final String SUPPORTED_VERSION = "1.0";
}
