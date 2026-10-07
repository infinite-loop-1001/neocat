package com.neocat.client;

import java.util.Objects;

/**
 * 一次本地事务记录（对应协议 TRANSACTION 节点）。
 *
 * <p>语义对应 PRD 02 §3 与 PRD 03 §7：耗时参与分位；非成功状态派生 Problem 异常类；
 * URL/SQL/CALL/CACHE 超过平台阈值还会派生慢类。
 *
 * <p>**记录过程永不向业务抛异常**：任何内部错误都被吞掉。
 */
public class Transaction {

    /** 成功状态常量，与协议约定一致。 */
    public static final String SUCCESS = "0";

    /** 失败状态常量。 */
    public static final String FAILURE = "ERROR";

    private final NeoCat cat;

    private final String type;

    private final String name;

    private final long start;

    private String status;

    private Throwable exception;

    private long duration;

    private boolean completed;

    Transaction(NeoCat cat, String type, String name) {
        this.cat = cat;
        this.type = type;
        this.name = name;
        this.start = System.currentTimeMillis();
        this.status = SUCCESS;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** 记录异常：异常名成为 Problem 的聚合键（PRD 03 §9）。 */
    public void setException(Throwable exception) {
        this.exception = exception;
        if (Objects.isNull(this.status) || SUCCESS.equals(this.status)) {
            this.status = FAILURE;
        }
    }

    /** 完成记录并交给 SDK 入队；重复调用无效。 */
    public void complete() {
        if (completed) {
            return;
        }
        completed = true;
        duration = Math.max(0, System.currentTimeMillis() - start);
        try {
            cat.enqueueTransaction(type, name, Objects.isNull(status) ? SUCCESS : status, duration, exception);
        } catch (Throwable ignored) {
            // 记录失败绝不影响业务
        }
    }

    public String getType() {
        return type;
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

    public boolean isCompleted() {
        return completed;
    }
}



