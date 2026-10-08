/**
 * 上报接收模块（PRD 02 §4–8，链路 11、12、14、15）。
 *
 * <p>关键顺序不变式：合法身份校验通过后**立即**调用目录发现，
 * 之后才尝试入队 —— 这样即使队列满导致整棵树被丢弃，
 * 服务与实例仍可被发现（PRD 02 §5）。
 *
 * <p>注意：本模块**不依赖 catalog 模块**。发现能力通过自有的
 * {@code CatalogGateway} 抽象提供，具体实现由装配层注入
 * （见 {@code ingest/infra/IngestWiring} 和 {@code catalog/api/internal}）。这让上报接收链路可以
 * 独立测试与独立演进，也避免了 ingest ↔ catalog 的双向耦合。
 */
@ApplicationModule(
        displayName = "Ingest",
        allowedDependencies = {"common", "common :: error", "common :: config",
                "common :: time", "common :: queue", "catalog :: internal", "trace :: internal", "platform :: platform", "protocol :: v1"})
package com.neocat.ingest;

import org.springframework.modulith.ApplicationModule;
