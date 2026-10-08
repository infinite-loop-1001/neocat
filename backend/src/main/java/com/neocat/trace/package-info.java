/**
 * Trace 存储与组装模块（PRD 02 §10、PRD 03 §11，链路 17、22）。
 *
 * <p>只依赖 {@code core}。取样与组装结果以领域对象形式对外提供，
 * 因此 {@code query} 通过本模块的 {@code domain} 具名接口读取，
 * 而不是直接访问存储。
 */
@ApplicationModule(
        displayName = "Trace",
        allowedDependencies = {"common", "common :: error", "common :: config", "common :: time", "common :: queue"})
package com.neocat.trace;

import org.springframework.modulith.ApplicationModule;
