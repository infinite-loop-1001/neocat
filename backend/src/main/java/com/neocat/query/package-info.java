/**
 * 查询模块（PRD 03、PRD 04，链路 17–23）。
 *
 * <p>依赖 {@code core}、{@code analysis}（聚合行与分位分布）、{@code trace}（取样）
 * 与 {@code platform}（平台时区）。
 *
 * <p>**本模块是唯一的报表读模型出口**：{@code dashboard} 与 {@code alert}
 * 都必须通过本模块取数，不得直连 ClickHouse 报表表 —— 这样
 * 「时间桶、QPS 口径、缺数语义、分位合并」只有一份实现。
 */
@ApplicationModule(
        displayName = "Query",
        allowedDependencies = {"common", "common :: error", "common :: config", "common :: time", "common :: queue", "analysis :: analysis", "trace :: trace", "trace :: config", "platform :: platform", "catalog :: catalog"})
package com.neocat.query;

import org.springframework.modulith.ApplicationModule;
