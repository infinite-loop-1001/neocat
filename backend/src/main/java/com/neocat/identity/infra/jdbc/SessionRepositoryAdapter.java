package com.neocat.identity.infra.jdbc;

import com.neocat.identity.domain.session.Session;
import com.neocat.identity.domain.session.SessionRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

/**
 * MySQL-backed sessions. Expiry is exclusive and renewal never revives an expired session.
 */
@Repository
public class SessionRepositoryAdapter implements SessionRepository {
    private final SessionMapper mapper;

    public SessionRepositoryAdapter(SessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Session create(long accountId, Instant at) {
        String id = UUID.randomUUID().toString();
        Instant expiresAt = at.plusSeconds(Session.SLIDING_SECONDS);
        mapper.insert(id, accountId, Timestamp.from(at), Timestamp.from(expiresAt));
        return new Session(id, accountId, expiresAt);
    }

    @Override
    public void touch(String sessionId, Instant at) {
        mapper.touchIfValid(sessionId, Timestamp.from(at),
                Timestamp.from(at.plusSeconds(Session.SLIDING_SECONDS)));
    }

    @Override
    public void invalidate(String sessionId) {
        mapper.deleteById(sessionId);
    }

    @Override
    public void invalidateAllOf(long accountId) {
        mapper.deleteByAccountId(accountId);
    }

    @Override
    public boolean isValid(String sessionId, Instant at) {
        Instant expires = expiresAt(sessionId);
        return Objects.nonNull(expires) && expires.isAfter(at);
    }

    @Override
    public Instant expiresAt(String sessionId) {
        SessionRow row = mapper.selectById(sessionId);
        return Objects.isNull(row) ? null : row.getExpiresAt().toInstant();
    }

    @Override
    public Session findSession(String sessionId) {
        SessionRow row = mapper.selectById(sessionId);
        return Objects.isNull(row) ? null : new Session(row.getId(), row.getAccountId(), row.getExpiresAt().toInstant());
    }

}
