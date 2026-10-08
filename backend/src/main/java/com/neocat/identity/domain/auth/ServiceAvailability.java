package com.neocat.identity.domain.auth;
import org.springframework.modulith.NamedInterface;

/**
 * 服务可用性判定（PRD 01 §4.1）：最近访问服务必须在「当前报表类型 + 当前时间范围」内有数据。
 * 由 catalog 模块实现，identity 只依赖该抽象。
 */
@FunctionalInterface
@NamedInterface("identity")
public interface ServiceAvailability {

    boolean hasData(String serviceName);
}
