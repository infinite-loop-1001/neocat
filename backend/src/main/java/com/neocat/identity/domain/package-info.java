/**
 * 账号与会话（跨模块共享契约，链路 2–6）。
 *
 * <p>{@code alert} 需要校验收件人是否启用，因此本包对外暴露。
 */
@NamedInterface("identity")
package com.neocat.identity.domain;

import org.springframework.modulith.NamedInterface;
