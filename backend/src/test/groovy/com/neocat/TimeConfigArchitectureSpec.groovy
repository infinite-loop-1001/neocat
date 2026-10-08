package com.neocat

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.springframework.modulith.core.ApplicationModules
import spock.lang.Specification
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition

/** 不被既有 organization 命名基线问题遮蔽的本轮架构验收。 */
class TimeConfigArchitectureSpec extends Specification {
    def "实际生产模块依赖无循环且跨模块配置只经过 config 契约"() {
        given:
        def classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages('com.neocat')
        when:
        SlicesRuleDefinition.slices()
                .matching('com.neocat.(*)..').should().beFreeOfCycles().check(classes)
        then:
        noExceptionThrown()
        classes.every { type ->
            type.directDependenciesFromSelf.findAll { it.targetClass.packageName.endsWith('.config') }.every { dep ->
                def owner = type.packageName.tokenize('.').drop(2).take(1)
                def target = dep.targetClass.packageName.tokenize('.').drop(2).take(1)
                owner == target || owner.isEmpty() ||
                        owner == ['analysis'] && target == ['ingest'] ||
                        owner == ['query'] && target == ['trace']
            }
        }
    }

    def "common 不反向依赖领域模块"() {
        given:
        def classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages('com.neocat')
        def business = ['identity', 'organization', 'platform', 'catalog', 'ingest',
                        'analysis', 'trace', 'query', 'dashboard', 'alert'] as Set
        expect:
        classes.findAll { it.name.startsWith('com.neocat.common.') }.every { type ->
            type.directDependenciesFromSelf.every { dependency ->
                !business.any { dependency.targetClass.name.startsWith('com.neocat.' + it + '.') }
            }
        }
    }

    def "新增配置具名接口只导出必要类型且 common time 导出静态入口"() {
        given:
        def modules = ApplicationModules.of(NeoCatApplication)
        def exported = { module, name ->
            modules.getModuleByName(module).orElseThrow().namedInterfaces
                    .find { it.name == name }.asJavaClasses().collect { it.simpleName } as Set
        }
        expect:
        exported('ingest', 'config') == ['IngestConfig'] as Set
        exported('trace', 'config') == ['TraceConfig'] as Set
        exported('common', 'time').contains('TimeProvider')
        !exported('common', 'time').contains('ClockProvider')
        !exported('common', 'time').contains('SystemClockProvider')
        exported('common', 'common-config-impl') == ['MySqlConnection'] as Set
        exported('common', 'config').isEmpty()
        !modules.stream().map { it.name }.toList().contains('bootstrap')
    }
}
