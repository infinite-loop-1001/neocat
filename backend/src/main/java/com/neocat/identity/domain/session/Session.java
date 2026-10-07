package com.neocat.identity.domain.session;

import java.time.Instant;

/**
 * 登录会话。滑动有效期为 30 分钟（PRD 01 §4.2）。
 */
@org.springframework.modulith.NamedInterface("identity")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class Session {
    private final String id;

    private final long accountId;

    private final Instant expiresAt;

    public Session(String id, long accountId, Instant expiresAt) {
        this.id = id;
        this.accountId = accountId;
        this.expiresAt = expiresAt;
    }

    public static final long SLIDING_SECONDS = 30 * 60;
}
