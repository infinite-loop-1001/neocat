package com.neocat

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import spock.lang.Specification

/** Offline registration only: does not prove real MySQL execution or transactions. */
class MapperRegistrationSpec extends Specification {
    def "全部 MyBatis XML 的 namespace 与结果类型在分包后仍能注册"() {
        given:
        def root = new File('src/main/resources/mapper')
        def files = []
        root.eachFileRecurse { if (it.name.endsWith('.xml')) files << it }
        def configuration = new Configuration()

        when:
        files.sort { it.path }.each { file ->
            file.withInputStream { stream ->
                new XMLMapperBuilder(stream, configuration, file.path, configuration.sqlFragments).parse()
            }
        }

        then:
        files.size() == 10
        files.each { file ->
            def namespace = (file.text =~ /namespace="([^"]+)"/)[0][1]
            assert configuration.hasMapper(Class.forName(namespace))
        }
        !configuration.resultMapNames.isEmpty()
    }
}
