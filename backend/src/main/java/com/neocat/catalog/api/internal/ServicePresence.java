package com.neocat.catalog.api.internal;

import java.time.Instant;

/** 登录落点判定所需的最小查询接口。 */
public interface ServicePresence {
    boolean hasRecentTransactions(String serviceName, Instant from, Instant to);
}
