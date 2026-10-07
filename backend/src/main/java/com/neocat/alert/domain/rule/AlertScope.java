package com.neocat.alert.domain.rule;

/**
 * 告警作用范围（PRD 06 §1）。
 *
 * <ul>
 *   <li>{@link #SERVICE} 服务告警：绑定一个服务的原始指标目标；任意登录用户可配置；</li>
 *   <li>{@link #ORGANIZATION} 组织告警：绑定该叶子大盘已引用的原始指标或卡片计算结果；
 *       仅叶子有效成员可配置。</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("alert")
public enum AlertScope {
    SERVICE,
    // fixme: 这里用大盘告警更合适一点
    ORGANIZATION
}
