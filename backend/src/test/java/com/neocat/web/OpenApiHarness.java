package com.neocat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocat.OpenApiConfiguration;
import com.neocat.alert.api.http.AlertController;
import com.neocat.alert.api.http.convert.AlertConvert;
import com.neocat.alert.domain.engine.MinutePointSource;
import com.neocat.alert.domain.engine.NotificationDispatcher;
import com.neocat.alert.domain.engine.PreviewService;
import com.neocat.alert.domain.recipient.RecipientGateway;
import com.neocat.alert.domain.recipient.RecipientService;
import com.neocat.alert.domain.rule.AlertLifecycleService;
import com.neocat.alert.domain.rule.AlertRuleRepository;
import com.neocat.alert.domain.rule.AlertRuleService;
import com.neocat.catalog.api.http.CatalogController;
import com.neocat.catalog.domain.service.CatalogService;
import com.neocat.dashboard.api.http.DashboardController;
import com.neocat.dashboard.api.http.convert.DashboardConvert;
import com.neocat.dashboard.domain.card.CardService;
import com.neocat.dashboard.domain.dashboard.DashboardService;
import com.neocat.identity.api.http.IdentityController;
import com.neocat.identity.api.http.UserAdminController;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.account.AccountService;
import com.neocat.identity.domain.auth.AuthenticationService;
import com.neocat.identity.domain.auth.ServiceAvailability;
import com.neocat.ingest.api.http.IngestController;
import com.neocat.ingest.domain.receive.IngestService;
import com.neocat.organization.api.http.OrgAdminController;
import com.neocat.organization.domain.lifecycle.OrgLifecycleService;
import com.neocat.organization.domain.membership.OrgMembershipService;
import com.neocat.organization.domain.tree.OrgNodeRepository;
import com.neocat.organization.domain.tree.OrgTreeService;
import com.neocat.platform.api.http.PlatformController;
import com.neocat.platform.domain.profile.PlatformService;
import com.neocat.query.api.http.MetricCountController;
import com.neocat.query.api.http.ReportController;
import com.neocat.query.api.http.convert.ReportConvert;
import com.neocat.query.infra.service.MetricCountQueryService;
import com.neocat.query.infra.service.ReportQueryService;
import com.neocat.trace.api.http.TraceController;
import com.neocat.trace.domain.tree.TraceAssembler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 真实生成 {@code /v3/api-docs} 的离线夹具。
 *
 * <p>装载全部对外 Controller 与 SpringDoc 的 OpenAPI 组件，依赖用 Mockito 替身，
 * 不连接 MySQL / ClickHouse / Apollo，也不引入 Swagger UI。
 * 断言放在 {@code OpenApiContractSpec}，此类的唯一职责是把文档生成出来。
 */
public final class OpenApiHarness {

    private OpenApiHarness() {
    }

    /** 生成 OpenAPI JSON；失败时抛出并携带响应内容，便于定位。 */
    public static String apiDocsJson() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("openapi-test", Map.of(
                "springdoc.api-docs.enabled", "true",
                "springdoc.paths-to-match", "/api/**",
                "springdoc.packages-to-scan", "com.neocat")));
        context.register(Harness.class, OpenApiConfiguration.class,
                SpringDocConfiguration.class, SpringDocConfigProperties.class, SpringDocWebMvcConfiguration.class,
                IdentityController.class, UserAdminController.class, OrgAdminController.class,
                PlatformController.class, CatalogController.class, IngestController.class,
                TraceController.class, ReportController.class, MetricCountController.class,
                DashboardController.class, AlertController.class);
        context.addBeanFactoryPostProcessor(stubs(json));
        context.refresh();

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var response = mvc.perform(MockMvcRequestBuilders.get(OpenApiConfiguration.API_DOCS_PATH)).andReturn().getResponse();
        String body = response.getContentAsString();
        if (!Objects.equals(response.getStatus(), 200)) {
            throw new IllegalStateException("生成文档失败：" + response.getStatus() + " " + body);
        }
        return body;
    }

    /** 在 Bean 实例化前登记替身，避免控制器因缺少依赖而装配失败。 */
    private static BeanFactoryPostProcessor stubs(ObjectMapper json) {
        List<Class<?>> types = List.of(
                AuthenticationService.class, AccountService.class, ServiceAvailability.class, AccountRepository.class,
                OrgTreeService.class, OrgLifecycleService.class, OrgMembershipService.class, OrgNodeRepository.class,
                PlatformService.class, CatalogService.class, IngestService.class, TraceAssembler.class,
                ReportQueryService.class, MetricCountQueryService.class, DashboardService.class, CardService.class,
                AlertRuleRepository.class, AlertRuleService.class, AlertLifecycleService.class, PreviewService.class,
                RecipientService.class, NotificationDispatcher.class, RecipientGateway.class, MinutePointSource.class);
        return beanFactory -> {
            for (Class<?> type : types) {
                beanFactory.registerSingleton(type.getSimpleName(), Mockito.mock(type));
            }
            // @DependsOn("alertConfig") / @DependsOn("traceConfig") 只要求 Bean 存在。
            beanFactory.registerSingleton("alertConfig", new Object());
            beanFactory.registerSingleton("traceConfig", new Object());
            beanFactory.registerSingleton("alertConvert", Mappers.getMapper(AlertConvert.class));
            beanFactory.registerSingleton("dashboardConvert", Mappers.getMapper(DashboardConvert.class));
            beanFactory.registerSingleton("reportConvert", new ReportConvert(json));
        };
    }

    @Configuration
    @EnableWebMvc
    static class Harness {
    }
}
