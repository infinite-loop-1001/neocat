/**
 * 上报协议的生成类（服务端视角）。
 *
 * <p>本包**不含手写代码**：类由 {@code protobuf-maven-plugin} 从仓库根
 * {@code proto/neocat/ingest/v1/ingest.proto} 生成。
 * {@code client-java} 的构建从**同一份** {@code .proto} 生成同一批类，
 * 因此两端不存在协议漂移的可能（方案 B：不设独立协议模块，各自生成）。
 *
 * <h2>为什么这个只有注解的文件存在</h2>
 *
 * <p>Spring Modulith 的模块依赖判定规则：声明
 * {@code allowedDependencies = {"protocol"}} 只允许依赖模块根包中的类型；
 * 而本包是 {@code com.neocat.protocol} 的**子包**，
 * 必须用 {@code @NamedInterface} 把它显式导出的具名接口，
 * 依赖方（{@code ingest}、{@code web}）才能写成 {@code "protocol :: v1"}。
 *
 * <p>若删掉本文件，{@code ModuleBoundarySpec} 会立即失败并报
 * 「Module 'ingest' depends on module 'protocol'」。
 *
 * <h2>为什么注解只放在服务端</h2>
 *
 * <p>{@code @ApplicationModule} / {@code @NamedInterface} 是**服务端框架关注点**，
 * 与协议本身无关。方案 B 下客户端在自己模块内生成类，
 * 本包不进入 SDK 工件，因此 {@code client-java} 的依赖树中
 * 不会出现任何 {@code org.springframework.modulith} 构件。
 */
@ApplicationModule(
        displayName = "Protocol",
        allowedDependencies = {})
@NamedInterface("v1")
package com.neocat.protocol.ingest.v1;

import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.NamedInterface;
