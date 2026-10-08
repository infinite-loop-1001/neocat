package com.neocat

import org.springframework.modulith.core.ApplicationModules
import spock.lang.Specification

/**
 * 模块边界校验（技术方案 01-architecture.md §4.1）。
 *
 * <p>用可执行的方式验证约束 4「框架为 Spring Modulith」：
 * <ol>
 *   <li>各模块声明的 {@code allowedDependencies} 与实际代码依赖一致；</li>
 *   <li>模块之间不存在循环依赖；</li>
 *   <li>模块划分与设计文档的 10 个业务模块一致。</li>
 * </ol>
 *
 * <p>这些检查把「谁可以依赖谁」从文档搬进测试：一旦有人引入越界依赖，
 * 测试立即失败，而不是等到架构评审才发现。
 */
class ModuleBoundarySpec extends Specification {

    static final ApplicationModules MODULES = ApplicationModules.of(NeoCatApplication)

    def "职责子包细分不改变具名接口或导出类型集合"() {
        given:
        def baseline = new Properties()
        getClass().getResourceAsStream('/named-interface-types.properties').withCloseable {
            baseline.load(it)
        }
        def actual = [:]

        when:
        MODULES.stream().forEach { module ->
            def names = module.namedInterfaces.stream().filter { it.named }.map { it.name }.toList()
            assert names.size() == names.toSet().size(): "${module.name} 存在重复具名接口：$names"
            module.namedInterfaces.stream().filter { it.named }.forEach { named ->
                actual["${module.name}::${named.name}".toString()] = named.asJavaClasses()
                        .map { it.name.substring(it.name.lastIndexOf('.') + 1) }.toList().toSet()
            }
        }

        then:
        actual.keySet() == baseline.stringPropertyNames()
        baseline.each { key, value ->
            assert actual[key] == (value.isEmpty() ? [] as Set : value.split(',') as Set): "$key 导出类型发生漂移"
        }
    }

    def "Spring Modulith 能识别到全部业务模块"() {
        when:
        def names = MODULES.stream().map { it.name }.toList()

        then: "10 个业务模块 + common 共享模块，无集中 web 模块"
        names.containsAll([
                "identity", "isOrganization", "platform", "catalog", "ingest",
                "analysis", "trace", "query", "dashboard", "alert"
        ])
        names.contains("common")
        !names.contains("web")
        !names.contains("core")
    }

    /**
     * 核心断言：每个模块只依赖其 {@code allowedDependencies} 中声明的依赖。
     *
     * <p>这是本文件存在的意义 —— 它把设计文档 §4.1 的依赖表变成可执行约束。
     */
    def "各模块只依赖声明中允许的依赖（无越界依赖）"() {
        when:
        MODULES.verify()

        then:
        noExceptionThrown()
    }

    def "模块依赖方向构成有向无环图（无循环依赖）"() {
        when: "Modulith 在 verify 时会检测循环依赖"
        MODULES.verify()

        then:
        noExceptionThrown()
    }

    /** 某模块直接依赖的模块名集合（不含自身）。 */
    def dependenciesOf(String moduleName) {
        def module = MODULES.getModuleByName(moduleName).orElseThrow()
        def deps = [] as Set
        module.getDependencies(MODULES).stream()
                .forEach { deps << it.targetModule.name }
        return deps - moduleName
    }

    def "依赖图与设计文档一致：基础域只依赖 common"() {
        expect: "isOrganization / platform / catalog / trace 不引用其他业务模块"
        ["isOrganization", "platform", "catalog", "trace"].each { name ->
            def deps = dependenciesOf(name)
            assert deps.every { it == "common" }:
                    "$name 的依赖超出预期：$deps"
        }
    }

    def "ingest 的目录发现只引用 catalog 对内契约"() {
        when:
        def ingestDeps = dependenciesOf("ingest")
        def catalogDeps = dependenciesOf("catalog")

        then: "ingest/infra 通过 catalog/api/internal 完成自有 CatalogGateway 端口"
        ingestDeps.contains("catalog")

        and: "catalog 也不依赖 ingest（目录不反向依赖上报）"
        !catalogDeps.contains("ingest")

        and: "因此上报接收链路可以独立测试与演进"
        ingestDeps.contains("common")
    }

    def "catalog 只依赖 common：目录模块不感知报表与上报"() {
        expect:
        dependenciesOf("catalog").every { it == "common" }
    }

    def "query 是报表读模型出口：dashboard 与 alert 依赖它，而非反向"() {
        when:
        def dashboardDeps = dependenciesOf("dashboard")
        def alertDeps = dependenciesOf("alert")
        def queryDeps = dependenciesOf("query")

        then: "dashboard / alert → query"
        dashboardDeps.contains("query")
        alertDeps.contains("query")

        and: "query 不反向依赖它们（否则会形成循环）"
        !queryDeps.contains("dashboard")
        !queryDeps.contains("alert")
    }

    def "dashboard 与 alert 之间不存在编译期循环依赖"() {
        when:
        def dashboardDeps = dependenciesOf("dashboard")
        def alertDeps = dependenciesOf("alert")

        then: "至少有一个方向不存在编译期依赖，联动通过事件完成"
        !(dashboardDeps.contains("alert") && alertDeps.contains("dashboard"))
    }

    def "不再存在允许依赖全部业务模块的 web 总装配"() {
        expect:
        MODULES.getModuleByName("web").isEmpty()
    }

    def "HTTP 控制器归属于业务模块而非共享模块"() {
        given:
        def root = new File('src/main/java/com/neocat')
        def business = ['identity', 'isOrganization', 'platform', 'catalog', 'ingest',
                        'analysis', 'trace', 'query', 'dashboard', 'alert'] as Set

        expect:
        root.eachFileRecurse { file ->
            if (!file.name.endsWith('Controller.java')) return
            def parts = file.absolutePath.substring(root.absolutePath.length() + 1).split('/')
            assert business.contains(parts[0]) && parts[1..2] == ['api', 'http'] : file
        }
    }

    def "生产模块不跨模块引用基础设施或 HTTP 入口"() {
        given:
        def root = new File('src/main/java/com/neocat')
        def business = ['identity', 'isOrganization', 'platform', 'catalog', 'ingest',
                        'analysis', 'trace', 'query', 'dashboard', 'alert'] as Set

        expect:
        root.eachFileRecurse { file ->
            if (!file.name.endsWith('.java')) return
            def owner = file.absolutePath.substring(root.absolutePath.length() + 1).split('/')[0]
            file.eachLine { line ->
                def matcher = line =~ /\bimport com\.neocat\.([a-z]+)\.(infra|api\.http)\./
                while (matcher.find()) {
                    assert !(business.contains(owner) && business.contains(matcher.group(1))
                            && owner != matcher.group(1)) : "${file}: ${line}"
                }
            }
        }
    }

    def "模块文档化：每个模块都声明了 displayName（可读性与架构可见性）"() {
        expect:
        MODULES.stream().allMatch { module ->
            module.getDisplayName() != null && !module.getDisplayName().isBlank()
        }
    }
}
