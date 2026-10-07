/**
 * 时间桶与范围（跨模块共享契约，技术方案 03 §4.2）。
 *
 * <p>所有时间范围解析与桶对齐都经此处，保证「平台时区对齐」
 * 与「左闭右开」只有一份实现。
 */
@org.springframework.modulith.NamedInterface("time")
package com.neocat.common.time;
