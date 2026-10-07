/**
 * 服务与实例目录（跨模块共享契约，链路 13）。
 *
 * <p>{@code ingest} 在接收路径上调用目录发现，{@code web} 读取目录列表，
 * 因此本包整体对外暴露。其中 {@code SeriesPresence} 是「按类型+范围是否有数据」
 * 的抽象，由报表侧实现，从而让目录模块不依赖 {@code query}。
 */
@org.springframework.modulith.NamedInterface("catalog")
package com.neocat.catalog.domain;
