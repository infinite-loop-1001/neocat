package com.neocat.common.config.impl;

import com.neocat.common.config.AlertConfig;
import com.neocat.common.config.AnalysisConfig;
import com.neocat.common.config.HeartbeatConfig;
import com.neocat.common.config.IngestConfig;
import com.neocat.common.config.MetricConfig;
import com.neocat.common.config.QueryConfig;
import com.neocat.common.config.ReportConfig;
import com.neocat.common.config.TraceConfig;
import link.cu1universe.dev.apollo.annotation.ApolloStaticValue;
import org.springframework.core.env.Environment;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;

/**
 * 启动期必需配置校验。
 *
 * <p>服务发现、远端读取、缓存回退都是 Apollo 客户端的职责，本类不重复实现，也不持有配置值：
 * 在 Apollo 客户端把远端配置写入 {@link Environment} 之后、业务 Bean 创建之前检查一遍。
 *
 * <p>为什么必须有这一步：Apollo 客户端在远端不可用时会退回本地缓存或空值并**照常启动**，
 * 而 {@code @ApolloStaticValue} 转换失败只记录错误、保留旧值。两者叠加会让缺失配置以 0/null
 * 静默进入运行期。这里让缺键或非法值在启动时直接失败。
 *
 * <p>键集合的单一事实源是动态配置类上的 {@link ApolloStaticValue}，因此新增字段自动纳入校验，
 * 不会再出现一份手写键清单与代码漂移。
 */
public final class ApolloConfigGuard {

    /** 框架与连接类必需键（不是 {@code @ApolloStaticValue} 动态配置）。 */
    public static final List<String> FRAMEWORK_KEYS = List.of(
            "server.port", "spring.datasource.url", "spring.datasource.username",
            "spring.datasource.password", "neocat.clickhouse.url", "neocat.clickhouse.username",
            "neocat.clickhouse.password", "mybatis.mapper-locations", "neocat.platform.init.timezone");

    /** 允许为空值的键：空口令是合法配置，不能被当成缺键。 */
    private static final List<String> ALLOW_EMPTY = List.of(
            "spring.datasource.password", "neocat.clickhouse.password");

    /** 按用途拆分的动态配置类；新增配置类要登记到这里才能被校验覆盖。 */
    public static final List<Class<?>> DYNAMIC_CONFIG_TYPES = List.of(IngestConfig.class,
            AnalysisConfig.class, ReportConfig.class, MetricConfig.class, TraceConfig.class,
            AlertConfig.class, QueryConfig.class, HeartbeatConfig.class);

    private ApolloConfigGuard() { }

    /** 校验必需配置；任何缺键或非法值都抛出，阻止带病启动。 */
    public static void validate(Environment environment) {
        List<String> problems = new ArrayList<>();
        for (String key : FRAMEWORK_KEYS) {
            String value = environment.getProperty(key);
            if (Objects.isNull(value) || (!ALLOW_EMPTY.contains(key) && value.isBlank())) {
                problems.add("缺少必需配置：" + key);
            }
        }
        for (Class<?> type : DYNAMIC_CONFIG_TYPES) {
            for (Field field : type.getDeclaredFields()) {
                validateField(environment, field, problems);
            }
        }
        validateServerPort(environment.getProperty("server.port"), problems);
        validateTimezone(environment.getProperty("neocat.platform.init.timezone"), problems);
        if (CollectionUtils.isNotEmpty(problems)) {
            throw new IllegalStateException("Apollo 配置校验失败：" + String.join("；", problems));
        }
    }

    private static void validateField(Environment environment, Field field, List<String> problems) {
        String key = placeholderKey(field);
        String raw = environment.getProperty(key);
        if (Objects.isNull(raw) || raw.isBlank()) {
            problems.add("缺少必需配置：" + key);
            return;
        }
        try {
            Class<?> type = field.getType();
            if (Objects.equals(type, int.class)) {
                if (Integer.parseInt(raw) < 1) {
                    problems.add("运行参数必须大于 0：" + key + "=" + raw);
                }
            } else if (Objects.equals(type, double.class)) {
                double value = Double.parseDouble(raw);
                if (!Double.isFinite(value) || value < 0 || value > 1) {
                    problems.add("比例参数必须在 0–1 之间：" + key + "=" + raw);
                }
            } else if (Objects.equals(type, boolean.class)
                    && !"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
                problems.add("布尔运行参数不合法：" + key + "=" + raw);
            }
        } catch (NumberFormatException e) {
            problems.add("运行参数不是合法数字：" + key + "=" + raw);
        }
    }

    private static void validateServerPort(String port, List<String> problems) {
        if (Objects.isNull(port) || port.isBlank()) {
            return;
        }
        try {
            int value = Integer.parseInt(port);
            if (value < 1 || value > 65535) {
                problems.add("server.port 不合法：" + port);
            }
        } catch (NumberFormatException e) {
            problems.add("server.port 不合法：" + port);
        }
    }

    private static void validateTimezone(String zone, List<String> problems) {
        if (Objects.isNull(zone) || zone.isBlank()) {
            return;
        }
        try {
            java.time.ZoneId.of(zone);
        } catch (RuntimeException e) {
            problems.add("时区不合法：neocat.platform.init.timezone=" + zone);
        }
    }

    /** 取注解中的单个占位符键，去掉 {@code ${}}。 */
    private static String placeholderKey(Field field) {
        String placeholder = field.getAnnotation(ApolloStaticValue.class).value();
        return placeholder.substring(2, placeholder.length() - 1);
    }
}
