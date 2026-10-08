/**
 * 查询域（跨模块共享契约，链路 17–23）。
 *
 * <p>{@code dashboard} 与 {@code alert} 通过本包取数：
 * 统计项、分位合并、时间桶、环比、质量标记都由此提供。
 * 这是「query 是唯一报表读模型出口」在包层面的落点。
 */
@NamedInterface("query")
package com.neocat.query.domain;

import org.springframework.modulith.NamedInterface;
