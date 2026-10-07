/**
 * 错误模型（跨模块共享契约）。
 *
 * <p>{@code ErrorCode} 是按业务域分段的稳定错误码和消息模板，
 * {@code NeocatException} 是各类具体业务异常的抽象基类。
 * web 层的错误码映射与各模块的校验都依赖它们。
 */
@org.springframework.modulith.NamedInterface("error")
package com.neocat.common.error;
