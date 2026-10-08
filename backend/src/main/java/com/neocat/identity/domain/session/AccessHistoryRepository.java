package com.neocat.identity.domain.session;

import java.time.Instant;
import java.util.List;
import org.springframework.modulith.NamedInterface;

/**
 * 最近访问服务记录（PRD 01 §4.1）：决定登录后进入某服务 Transaction 还是服务列表。
 */
@NamedInterface("identity")
public interface AccessHistoryRepository {

    void record(long accountId, String serviceName, Instant at);

    /** 按访问时间倒序返回该账号的最近服务名。 */
    List<String> recentServices(long accountId, int limit);
}
