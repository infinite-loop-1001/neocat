package com.neocat.trace.domain.tree;

/**
 * Trace 节点的可用性状态（PRD 02 §10、"缺数据语义" 00 §6）。
 *
 * <p>关键区分（技术方案 02 §7.2）：
 * <ul>
 *   <li>{@link #MISSING} 表示**从未收到**该 MessageTree —— 依赖统计仍然有效；</li>
 *   <li>{@link #EXPIRED} 表示**曾收到但超过留存期** —— 汇总仍可查，但下钻被禁用；</li>
 *   <li>{@link #PRESENT} 表示树可用。</li>
 * </ul>
 * 二者在界面与接口上表现不同，不能合并成一种"没有"。
 */
@org.springframework.modulith.NamedInterface("trace")
public enum NodeAvailability {
    PRESENT,
    MISSING,
    EXPIRED
}
