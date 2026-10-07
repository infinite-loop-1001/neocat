package com.neocat.client;

/**
 * 消息发送口。
 *
 * <p>实现负责把序列化后的批次通过 HTTP + Protobuf 发给平台。
 * **实现抛出的任何异常都必须被 SDK 吞掉**，绝不传播到业务线程。
 */
public interface MessageSender {

    void send(byte[] payload);
}
