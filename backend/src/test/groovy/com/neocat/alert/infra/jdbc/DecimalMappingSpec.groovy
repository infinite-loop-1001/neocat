package com.neocat.alert.infra.jdbc

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.apache.ibatis.type.BigDecimalTypeHandler
import org.apache.ibatis.type.JdbcType
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import com.neocat.alert.domain.rule.AlertRule
import com.neocat.alert.domain.rule.AlertScope
import com.neocat.alert.domain.rule.AlertTarget
import com.neocat.alert.domain.rule.AlertChannel
import com.neocat.alert.domain.rule.Combinator
import com.neocat.alert.domain.rule.Comparator
import com.neocat.alert.domain.rule.Condition
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

/** 仅离线验证 MyBatis 类型绑定，不连接真实数据库。 */
class DecimalMappingSpec extends Specification {
    def '领域与 Mapper 行往返不把大阈值转换回浮点'() {
        given:
        def threshold = new BigDecimal('99999999999999.999999')
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def draft = AlertRule.draft(AlertScope.SERVICE, null, 'precise', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'), Combinator.AND, 1,
                [new Condition(Stat.HITS, Comparator.EQ, threshold)], [], [AlertChannel.EMAIL])
        def conditionRow = new AlertConditionRow()
        conditionRow.setStat('HITS')
        conditionRow.setComparator('EQ')
        conditionRow.setThreshold(threshold)
        def storedRow

        when:
        adapter.save(draft)
        def restored = adapter.findById(1L)

        then:
        1 * mapper.insertRule(_) >> { args -> storedRow = args[0]; storedRow.id = 1L; 1 }
        1 * mapper.insertCondition(1L, 'HITS', 'EQ', { it instanceof BigDecimal && it.toPlainString() == '99999999999999.999999' })
        1 * mapper.selectRule(1L) >> { storedRow }
        1 * mapper.selectConditions(1L) >> [conditionRow]
        1 * mapper.selectRecipients(1L) >> []
        restored.getConditions()[0].getThreshold().toPlainString() == '99999999999999.999999'
        restored.getConditions()[0].matches(threshold)
    }

    def '告警 DECIMAL 列读写都选用 BigDecimalTypeHandler'() {
        given:
        def configuration = new Configuration()
        String path = 'src/main/resources/mapper/alert/AlertMapper.xml'
        new File(path).withInputStream { stream ->
            new XMLMapperBuilder(stream, configuration, path, configuration.sqlFragments).parse()
        }
        def mapping = configuration.getResultMap(AlertMapper.name + '.conditionRow').resultMappings
                .find { it.property == 'threshold' }
        def statement = Mock(PreparedStatement)
        def result = Mock(ResultSet)
        def threshold = new BigDecimal('99999999999999.999999')
        def insert = configuration.getMappedStatement(AlertMapper.name + '.insertCondition')
                .getBoundSql([ruleId: 1L, stat: 'HITS', comparator: 'EQ', threshold: threshold])
        def parameter = insert.parameterMappings.find { it.property == 'threshold' }

        when:
        parameter.typeHandler.setParameter(statement, 4, threshold, JdbcType.DECIMAL)
        def read = mapping.typeHandler.getResult(result, 'threshold')

        then:
        1 * statement.setBigDecimal(4, threshold)
        1 * result.getBigDecimal('threshold') >> threshold
        read.toPlainString() == '99999999999999.999999'
        mapping.javaType == BigDecimal
        mapping.typeHandler instanceof BigDecimalTypeHandler
        parameter.typeHandler instanceof BigDecimalTypeHandler
    }
}
