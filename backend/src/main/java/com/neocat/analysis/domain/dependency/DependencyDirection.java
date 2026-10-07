package com.neocat.analysis.domain.dependency;

/**
 * 依赖方向（PRD 04 §8）：下游 = 当前服务调用的服务；上游 = 调用当前服务的服务。
 */
@org.springframework.modulith.NamedInterface("analysis")
public enum DependencyDirection {
    UPSTREAM,
    DOWNSTREAM
}
