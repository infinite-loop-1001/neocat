/**
 * Trace 领域模型与组装（跨模块共享契约）。
 *
 * <p>{@code query} 与 {@code web} 需要读取树、取样与组装结果。
 * 把这些类型声明为具名接口，明确它们是**稳定契约**而非内部实现。
 */
@NamedInterface("trace")
package com.neocat.trace.domain;

import org.springframework.modulith.NamedInterface;
