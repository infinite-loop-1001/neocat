/**
 * 服务与实例目录模块（PRD 02 §5，链路 13）。
 *
 * <p>只依赖 {@code core}。「某服务在某类型+范围是否有数据」通过
 * {@code SeriesPresence} 抽象由报表侧实现，因此目录模块不依赖 {@code query}。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Catalog",
        allowedDependencies = {"common", "common :: error", "common :: config", "common :: time", "common :: queue"})
package com.neocat.catalog;
