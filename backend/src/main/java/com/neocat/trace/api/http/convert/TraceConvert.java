package com.neocat.trace.api.http.convert;

import com.neocat.trace.api.http.dto.TraceDtos.*;
import com.neocat.trace.domain.tree.TraceAssembler;
import com.neocat.trace.domain.tree.TraceTreeNode;
import com.neocat.trace.domain.tree.TraceNode;
import java.util.List;

public final class TraceConvert {
    private TraceConvert() {
    }

    public static TraceResponse response(String messageId, TraceAssembler.AssemblyResult result) {
        return new TraceResponse(messageId, false, result.usable() ? List.of(node(result.getRoot())) : List.of(),
                result.usable() ? result.getRoot().countMissing() : 0,
                result.usable() ? result.getRoot().countExpired() : 0);
    }

    private static Node node(TraceTreeNode node) {
        return new Node(node.messageId(), node.serviceName(), node.instanceId(), node.availability().name(),
                node.missingReason(), node.treeTimestamp(), node.spans().stream().map(TraceConvert::span).toList(),
                node.children().stream().map(TraceConvert::node).toList());
    }

    private static Span span(TraceNode span) {
        return new Span(span.getNodeId(), span.getKind(), span.getCategory(), span.getName(), span.getStatus(),
                span.getDurationMs(), span.getDetail(), span.getTimestamp());
    }
}
