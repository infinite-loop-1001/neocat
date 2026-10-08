/**
 * 有界队列实现（跨模块共享）。
 *
 * <p>装配层需要构造队列工厂，因此本包对外暴露。
 */
@NamedInterface("common-queue-impl")
package com.neocat.common.queue.impl;

import org.springframework.modulith.NamedInterface;
