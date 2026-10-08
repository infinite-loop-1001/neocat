package com.neocat.identity.infra.jdbc;

import com.neocat.identity.domain.session.AccessHistoryRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 最近访问服务仓储的 MyBatis 适配器（表 {@code nc_access_history}）。
 *
 * <p>按访问时间倒序返回，且**去重**：同一服务多次访问只保留最新一次，
 * 因为登录落点只需要「最近访问过哪些服务」。
 */
@Repository
public class AccessHistoryRepositoryAdapter implements AccessHistoryRepository {

    private final AccessHistoryMapper mapper;

    public AccessHistoryRepositoryAdapter(AccessHistoryMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public void record(long accountId, String serviceName, Instant at) {
        // 主键为 (account_id, service_name)，因此同一服务重复访问只更新时间
        mapper.upsert(accountId, serviceName, Timestamp.from(at));
    }
    @Override
    public List<String> recentServices(long accountId, int limit) {
        return mapper.selectRecentServices(accountId, Math.max(1, limit));
    }
}
