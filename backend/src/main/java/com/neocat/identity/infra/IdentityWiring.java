package com.neocat.identity.infra;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.identity.domain.auth.ServiceAvailability;

import org.springframework.context.annotation.Bean;
import com.neocat.catalog.api.internal.ServicePresence;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentityWiring {
    /**
     * 登录落点判定（PRD 01 §4.1）：最近访问服务必须在当前范围内有数据。
     *
     * <p>签名与目录的公开查询契约不同，因此需要显式适配：
     * 登录落点用「最近 1 小时的 Transaction 是否有数据」作为可用性判据。
     */
    @Bean
    public ServiceAvailability serviceAvailability(ServicePresence presence) {
        return serviceName -> {
            Instant now = TimeProvider.now();
            return presence.hasRecentTransactions(serviceName, now.minusSeconds(3600), now);
        };
    }
}
