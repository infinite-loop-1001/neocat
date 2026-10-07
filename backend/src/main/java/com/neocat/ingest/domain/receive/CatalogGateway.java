package com.neocat.ingest.domain.receive;

import java.time.Instant;

/**
 * 上报路径上的身份发现网关。
 *
 * <p>由 catalog 模块实现；ingest 依赖该抽象以保证
 * 「合法身份校验通过后立即更新服务/实例目录」这一顺序要求在接收链路中显式可见
 * （PRD 02 §5）。具体实现必须同步完成发现，不得异步化，否则队列满时无法发现。
 */
@FunctionalInterface
@org.springframework.modulith.NamedInterface("tree")
public interface CatalogGateway {

    void discover(String serviceName, String instanceId, Instant at);
}
