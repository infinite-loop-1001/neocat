package com.neocat.client;

/**
 * 供 Transaction 使用的返回句柄。
 */
public final class RemoteCallHandle {

    private final NeoCat cat;

    private final com.neocat.client.RemoteCall delegate;

    private final String downstreamService;

    private final String callType;

    private final String name;

    RemoteCallHandle(NeoCat cat, String downstreamService, String callType, String name) {
        this.cat = cat;
        this.downstreamService = downstreamService;
        this.callType = callType;
        this.name = name;
        this.delegate = new com.neocat.client.RemoteCall(downstreamService, callType, name);
    }

    public void setStatus(String status) {
        delegate.setStatus(status);
    }

    public void complete() {
        delegate.complete();
        cat.enqueueRemoteCall(downstreamService, callType, name,
                delegate.getStatus(), delegate.getDuration());
    }

    public long getDuration() {
        return delegate.getDuration();
    }
}
