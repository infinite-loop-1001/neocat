package com.neocat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.ctrip.framework.apollo.spring.annotation.EnableApolloConfig;

/**
 * NeoCat 单进程应用入口（技术方案 01-architecture.md §1.1、§6）。
 *
 * <p>进程内同时承载上报接收与有界队列、RealtimeConsumer 扇出与 7 类分析器、
 * 当前小时内存报表与分钟落库、分钟告警判定与小时/日/周/月滚动。
 *
 * <p>Spring Modulith 以包为模块边界；跨模块调用只走公开接口或应用事件。
 *
 * <p>配置来自 Apollo：服务发现、远端读取与本地缓存回退都由 Apollo 客户端负责，
 * 应用不重复实现。{@code app.id} 由 {@code -Dapp.id}、{@code APP_ID} 或
 * {@code META-INF/app.properties} 提供，{@code apollo.meta} 由 {@code -Dapollo.meta}
 * 或 {@code APOLLO_META} 提供；两者都是部署参数，不在此写死。
 */
@SpringBootApplication
@EnableScheduling
@EnableApolloConfig
public class NeoCatApplication {

    public static void main(String[] args) {
        // common-apollo 的处理器会把原始配置值打进 INFO 日志，令牌等敏感值不能进入日志。
        // 保留 ERROR 以便看到转换失败。
        System.setProperty("logging.level.link.cu1universe.dev.apollo.processor.ApolloStaticValueProcessor", "ERROR");
        new SpringApplication(NeoCatApplication.class).run(args);
    }
}
