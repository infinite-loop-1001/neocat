/**
 * 上报树模型（跨模块共享契约）。
 *
 * <p>本包整体作为**具名接口**暴露：{@code ingest} 与 {@code analysis}
 * 都需要同一套树/节点结构，把它声明为具名接口比让两个模块各定义一套更能避免漂移。
 *
 * <p>这也是 PRD 02 §2「MessageTree 语义」在代码层的落点。
 */
@org.springframework.modulith.NamedInterface("tree")
package com.neocat.ingest.domain;
