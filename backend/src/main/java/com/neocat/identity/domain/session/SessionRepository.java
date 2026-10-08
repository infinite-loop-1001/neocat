package com.neocat.identity.domain.session;

import java.time.Instant;
import org.springframework.modulith.NamedInterface;

@NamedInterface("identity")
public interface SessionRepository {

    Session create(long accountId, Instant at);

    void touch(String sessionId, Instant at);

    void invalidate(String sessionId);

    void invalidateAllOf(long accountId);

    boolean isValid(String sessionId, Instant at);

    Instant expiresAt(String sessionId);

    /** 按会话 ID 查会话本身；不存在返回 null。 */
    Session findSession(String sessionId);
}
