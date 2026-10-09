package com.neocat.dashboard.domain.event;

import org.springframework.modulith.NamedInterface;

/**
 * 卡片变更事件（PRD 05 §8，技术方案 02 §4.2）。
 *
 * <p>dashboard 通过事件通知 alert，避免模块间双向编译依赖。
 * 事件载荷必须包含足以让 alert 重新解析目标与判断失效的全部信息。
 */
@NamedInterface("dashboard")
public sealed interface CardEvent permits CardTargetChanged, CardDeleted {

    long getCardId();

    long getDashboardId();

    long getOrgId();

}