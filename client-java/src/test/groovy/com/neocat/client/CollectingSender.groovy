package com.neocat.client

import com.neocat.protocol.ingest.v1.IngestRequest
import com.neocat.protocol.ingest.v1.MessageTree

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 测试替身：收集发送的 payload，并解析为协议对象。
 */
class CollectingSender implements MessageSender {

    private final List<byte[]> payloads = new CopyOnWriteArrayList<>()

    @Override
    void send(byte[] payload) {
        payloads << payload
    }

    List<byte[]> payloads() {
        return payloads
    }

    List<IngestRequest> requests() {
        return payloads.collect { IngestRequest.parseFrom(it) }
    }

    List<MessageTree> trees() {
        return requests().collectMany { it.treesList }
    }
}
