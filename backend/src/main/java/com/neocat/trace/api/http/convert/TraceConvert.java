package com.neocat.trace.api.http.convert;

import com.google.common.collect.Lists;
import com.neocat.trace.api.http.dto.*;
import com.neocat.trace.domain.tree.TraceTreeNode;
import com.neocat.trace.domain.tree.TraceNode;
import com.neocat.trace.api.http.dto.Node;
import com.neocat.trace.api.http.dto.Span;
import com.neocat.trace.api.http.dto.TraceResponse;
import com.neocat.trace.domain.tree.AssemblyResult;

public final class TraceConvert {
    private TraceConvert() {
    }

    public static TraceResponse response(String messageId, AssemblyResult result) {
        return new TraceResponse(messageId, false, result.usable() ?
                Lists.newArrayList(node(result.getRoot())) : Lists.newArrayList(),
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
