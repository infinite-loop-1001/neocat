package com.neocat.client;

/**
 * 一次记录（Transaction / Event / Metric / Heartbeat / RemoteCall）的公共载体。
 *
 * <p>所有记录在 {@code complete()} 或同步写入时被组装为一棵 MessageTree 并入队。
 * 记录对象本身不抛异常给业务。
 */
public interface Record {

    /** 是否为同步记录（Event / Metric / Heartbeat 立即入队）。 */
    boolean immediate();
}
