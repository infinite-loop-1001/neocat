package com.neocat.identity.domain.session;

import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 登录会话。滑动有效期为 30 分钟（PRD 01 §4.2）。
 */
@NamedInterface("identity")
@Getter
@EqualsAndHashCode
@ToString
public class Session {
    private final String id;

    private final String userAccount;

    private final Instant expiresAt;

    public Session(String id, String userAccount, Instant expiresAt) {
        this.id = id;
        this.userAccount = userAccount;
        this.expiresAt = expiresAt;
    }

    public static final long SLIDING_SECONDS = 30 * 60;
}
