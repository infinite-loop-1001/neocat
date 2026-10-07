/**
 * 分析模块（PRD 02 §9、PRD 03 §7–10、PRD 04，链路 16、19–22）。
 *
 * <p>依赖 {@code core}、{@code ingest}（树模型）与 {@code platform}（慢阈值）。
 * 本模块持有上报协议的树结构作为分析输入，因此对 {@code ingest} 的
 * {@code domain} 包有编译依赖 —— 这是「树模型是跨模块共享契约」的体现，
 * 已在 {@code ingest/domain/package-info.java} 中声明为具名接口。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Analysis",
        allowedDependencies = {"common", "common :: error", "common :: config", "common :: time", "common :: queue", "ingest :: tree", "platform :: platform", "platform :: internal"})
package com.neocat.analysis;
