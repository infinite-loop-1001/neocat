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

    def '全部 116 个类型保持所属模块的独立顶层文件，旧嵌套类型不残留'() {
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
            assert type.simpleName == newName.substring(newName.lastIndexOf('.') + 1)
            assert oldName.tokenize('.')[0..2] == newName.tokenize('.')[0..2]
            assert new File("src/main/java/${newName.replace('.', '/')}.java").isFile()
            def oldBinaryName = oldName.substring(0, oldName.lastIndexOf('.')) + '$' + oldName.substring(oldName.lastIndexOf('.') + 1)
            assert type.classLoader.getResource(oldBinaryName.replace('.', '/') + '.class') == null
        }
    }

    def '归包类型使用已约定的职责子包且旧平铺路径没有残留'() {
        given:
        def migrations = properties('/type-package-migrations.properties')
        def originals = properties('/standalone-type-migrations.properties')

        expect:
        migrations.size() == 100
        migrations.each { oldName, newName ->
            def type = Class.forName(newName)
            assert type.enclosingClass == null
            assert type.simpleName == oldName.substring(oldName.lastIndexOf('.') + 1)
            assert oldName.tokenize('.')[0..2] == newName.tokenize('.')[0..2]
            assert newName.startsWith(oldName.substring(0, oldName.lastIndexOf('.')) + '.')
            assert new File("src/main/java/${newName.replace('.', '/')}.java").isFile()
            assert !new File("src/main/java/${oldName.replace('.', '/')}.java").exists()
            assert type.classLoader.getResource(oldName.replace('.', '/') + '.class') == null
        }
        originals.values().findAll { it.contains('.api.http.dto.') && !it.contains('.trace.') }.each { name ->
            assert name.tokenize('.').size() == 8: "HTTP DTO 未归入用途子包：$name"
        }
        // 单一用途的 Trace 类型族保持完整，不为了层数继续拆开。
        originals.values().findAll { it.contains('.trace.api.http.dto.') }.each { name ->
            assert name.startsWith('com.neocat.trace.api.http.dto.') && name.tokenize('.').size() == 7
        }
    }

    def '归包类型保留原具名接口，不缩减或新增迁移类型的导出集合'() {
        given:
        def baseline = properties('/named-interface-types.properties')
        def migrations = properties('/type-package-migrations.properties')
        def movedNames = migrations.values().collect { it.substring(it.lastIndexOf('.') + 1) } as Set
        def modules = ApplicationModules.of(NeoCatApplication)
        def actual = [:]
        modules.stream().forEach { module ->
            module.namedInterfaces.stream().filter { it.named }.forEach { named ->
                actual["${module.name}::${named.name}".toString()] = named.asJavaClasses()
                        .map { it.name.substring(it.name.lastIndexOf('.') + 1) }.toList().toSet()
            }
        }

        expect:
        // 全仓基线已有不一致，继续由 ModuleBoundarySpec 报错；本规格只隔离并严格验证归包类型。
        def expectedMoved = [:]
        baseline.each { key, value ->
            def names = (value.isEmpty() ? [] as Set : value.split(',') as Set).intersect(movedNames)
            if (!names.isEmpty()) {
                def actualKey = key == 'organization::organization' ? 'organization::isOrganization' : key
                expectedMoved[actualKey] = names
            }
        }
        def actualMoved = actual.collectEntries { key, names -> [key, names.intersect(movedNames)] }
                .findAll { key, names -> !names.isEmpty() }
        actualMoved == expectedMoved
    }

    def 'sealed 类型拆分保持原允许子类型集合'() {
        expect:
        Formula.permittedSubclasses*.simpleName as Set == ['Ref', 'Constant', 'Binary', 'Aggregate'] as Set
        RangeSpec.permittedSubclasses*.simpleName as Set == ['QuickRange', 'Hour', 'Day', 'Week', 'Month', 'Explicit'] as Set
        CardEvent.permittedSubclasses*.simpleName as Set == ['CardDeleted', 'CardTargetChanged'] as Set
        RecipientEvent.permittedSubclasses*.simpleName as Set == ['UserDisabled', 'UserEnabled', 'OrgMembershipChanged', 'OrgDeleted'] as Set
    }
}
