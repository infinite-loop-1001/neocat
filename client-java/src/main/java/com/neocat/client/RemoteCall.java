package com.neocat.client;

/**
 * 跨服务调用记录（对应协议 REMOTE_CALL 节点）。对应 PRD 02 §2。
 */
public class RemoteCall {

    private final String downstreamService;

    private final String callType;

    private final String name;

    private final long start;

    private String status;

    private long duration;

    private boolean completed;

    RemoteCall(String downstreamService, String callType, String name) {
        this.downstreamService = downstreamService;
        this.callType = callType;
        this.name = name;
        this.start = System.currentTimeMillis();
        this.status = Transaction.SUCCESS;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void complete() {
        if (completed) {
            return;
        }
        completed = true;
        duration = Math.max(0, System.currentTimeMillis() - start);
    }

    public String getDownstreamService() {
        return downstreamService;
    }

    public String getCallType() {
        return callType;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public long getDuration() {
        return duration;
    }
}