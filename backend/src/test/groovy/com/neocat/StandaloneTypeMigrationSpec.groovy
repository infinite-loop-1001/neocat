package com.neocat

import com.neocat.alert.domain.recipient.RecipientEvent
import com.neocat.common.time.range.RangeSpec
import com.neocat.dashboard.domain.event.CardEvent
import com.neocat.dashboard.domain.formula.Formula
import org.springframework.modulith.core.ApplicationModules
import spock.lang.Specification

/** 拆分回归不绕过 ModuleBoundarySpec；单独核对全部迁移与原公开类型的导出身份。 */
class StandaloneTypeMigrationSpec extends Specification {
    private static Properties properties(String resource) {
        def values = new Properties()
        StandaloneTypeMigrationSpec.getResourceAsStream(resource).withCloseable { values.load(it) }
        values
    }

    def '全部 116 个类型迁移为同包同名顶层文件，旧嵌套类型不残留'() {
        given:
        def migrations = properties('/standalone-type-migrations.properties')

        expect:
        migrations.size() == 116
        migrations.each { oldName, newName ->
            if (newName.startsWith('com.neocat.client.')) {
                assert new File("../client-java/src/main/java/${newName.replace('.', '/')}.java").isFile()
                return // SDK 类型在独立模块中编译与验证，不向后端测试 classpath 添加 SDK。
            }
            def type = Class.forName(newName)
            assert type.enclosingClass == null
            assert type.simpleName == oldName.substring(oldName.lastIndexOf('.') + 1)
            assert oldName.startsWith(type.packageName + '.')
            assert new File("src/main/java/${newName.replace('.', '/')}.java").isFile()
            def oldBinaryName = oldName.substring(0, oldName.lastIndexOf('.')) + '$' + type.simpleName
            assert type.classLoader.getResource(oldBinaryName.replace('.', '/') + '.class') == null
        }
    }

    def '拆分后的公开类型保留原具名接口，不缩减或新增导出集合'() {
        given:
        def baseline = properties('/named-interface-types.properties')
        def modules = ApplicationModules.of(NeoCatApplication)
        def actual = [:]
        modules.stream().forEach { module ->
            module.namedInterfaces.stream().filter { it.named }.forEach { named ->
                actual["${module.name}::${named.name}".toString()] = named.asJavaClasses()
                        .map { it.name.substring(it.name.lastIndexOf('.') + 1) }.toList().toSet()
            }
        }

        expect:
        // 已存在的命名不一致由 ModuleBoundarySpec 持续报错；这里只隔离该缺口，验证迁移没有新的导出漂移。
        actual.keySet() == (baseline.stringPropertyNames().collect {
            it == 'organization::organization' ? 'organization::isOrganization' : it
        } as Set)
        baseline.each { key, value ->
            def actualKey = key == 'organization::organization' ? 'organization::isOrganization' : key
            assert actual[actualKey] == (value.isEmpty() ? [] as Set : value.split(',') as Set): "$key 导出类型漂移"
        }
    }

    def 'sealed 类型拆分保持原允许子类型集合'() {
        expect:
        Formula.permittedSubclasses*.simpleName as Set == ['Ref', 'Constant', 'Binary', 'Aggregate'] as Set
        RangeSpec.permittedSubclasses*.simpleName as Set == ['QuickRange', 'Hour', 'Day', 'Week', 'Month', 'Explicit'] as Set
        CardEvent.permittedSubclasses*.simpleName as Set == ['CardDeleted', 'CardTargetChanged'] as Set
        RecipientEvent.permittedSubclasses*.simpleName as Set == ['UserDisabled', 'UserEnabled', 'OrgMembershipChanged', 'OrgDeleted'] as Set
    }
}
