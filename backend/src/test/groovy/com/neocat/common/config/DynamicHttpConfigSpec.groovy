package com.neocat.common.config

import com.neocat.trace.api.http.TraceController
import com.neocat.trace.domain.tree.TraceAssembler
import com.neocat.trace.domain.sample.SampleService
import com.neocat.query.infra.port.SamplePort
import com.neocat.alert.infra.job.AlertEvaluationJob
import com.neocat.alert.domain.engine.AlertEngine
import com.neocat.alert.domain.rule.AlertRuleRepository
import spock.lang.Specification
import java.time.*

class DynamicHttpConfigSpec extends Specification {
    def "同一报表控制器默认取样条数热更新，显式 limit 保持优先"() {
        given:
        def port = Mock(SamplePort)
        def controller = new com.neocat.query.api.http.ReportController(null,
                new com.neocat.common.time.bucket.DefaultTimeBucketResolver(), null, null, null, null, port,
                { ZoneOffset.UTC } as java.util.function.Supplier)

        when:
        controller.samples('s', 'TRANSACTION', 'URL', 'n', 'RECENT_1H', null)

        then:
        1 * port.samples('s', 'URL', 'n', _, _, 30) >> []

        when:
        TraceConfig.SAMPLE_ROWS = 9
        controller.samples('s', 'TRANSACTION', 'URL', 'n', 'RECENT_1H', null)
        controller.samples('s', 'TRANSACTION', 'URL', 'n', 'RECENT_1H', 4)

        then:
        1 * port.samples('s', 'URL', 'n', _, _, 9) >> []
        1 * port.samples('s', 'URL', 'n', _, _, 4) >> []
    }

    def "同一告警预览控制器更新后使用新延迟"() {
        given:
        def preview = Mock(com.neocat.alert.domain.engine.PreviewService)
        def convert = org.mapstruct.factory.Mappers.getMapper(com.neocat.alert.api.http.convert.AlertConvert)
        def controller = new com.neocat.alert.api.http.AlertController(null, null, null, preview,
                null, null, null, Clock.fixed(Instant.parse('2026-10-03T02:02:30Z'), ZoneOffset.UTC), convert)
        def target = new com.neocat.alert.api.http.dto.AlertDtos.TargetDraft('RAW_METRIC', 0, 's', 'TRANSACTION', 'URL', 'n', [])
        def condition = new com.neocat.alert.api.http.dto.AlertDtos.ConditionDraft('hits', 'GT', 0)
        def draft = new com.neocat.alert.api.http.dto.AlertDtos.AlertDraft('SERVICE', null, 'r', '',
                target, 'AND', 1, [condition], [], [])

        when:
        controller.preview(draft)

        then:
        1 * preview.preview(_, Instant.parse('2026-10-03T02:02:00Z').toEpochMilli()) >>
                com.neocat.alert.domain.engine.PreviewResult.insufficient([])

        when:
        AlertConfig.EVALUATE_DELAY_SECONDS = 90
        controller.preview(draft)

        then:
        1 * preview.preview(_, Instant.parse('2026-10-03T02:01:00Z').toEpochMilli()) >>
                com.neocat.alert.domain.engine.PreviewResult.insufficient([])
    }

    def "已创建的 Trace HTTP 控制器每次读取当前留存期"() {
        given:
        def assembler = Mock(TraceAssembler)
        def controller = new TraceController(assembler, Clock.systemUTC())

        when:
        controller.trace('one')

        then:
        1 * assembler.assemble('one', _ as Instant, Duration.ofDays(7)) >> new TraceAssembler.AssemblyResult(null, false, true)
        thrown(com.neocat.common.error.exception.ResourceNotFoundException)

        when:
        TraceConfig.RETENTION_DAYS = 2
        controller.trace('two')

        then:
        1 * assembler.assemble('two', _ as Instant, Duration.ofDays(2)) >> new TraceAssembler.AssemblyResult(null, false, true)
        thrown(com.neocat.common.error.exception.ResourceNotFoundException)
    }

    def "已创建的取样端口不保存留存 Duration"() {
        given:
        def samples = Mock(SampleService)
        def port = SamplePort.of(samples)

        when:
        TraceConfig.RETENTION_DAYS = 4
        port.samples('s', 'URL', 'n', Instant.EPOCH, Instant.EPOCH.plusSeconds(60), 30)

        then:
        1 * samples.samples(_, _ as Instant, Duration.ofDays(4)) >> []
    }

    def "已创建告警任务每次直接读取动态判定延迟"() {
        given:
        def repository = Mock(AlertRuleRepository)
        def job = new AlertEvaluationJob(repository, Mock(AlertEngine),
            Clock.fixed(Instant.parse('2026-10-03T02:02:30Z'), ZoneOffset.UTC))

        when:
        job.evaluate()
        AlertConfig.EVALUATE_DELAY_SECONDS = 90
        job.evaluate()

        then:
        1 * repository.enabledRules() >> []
        job.evaluationDelaySeconds() == 90
    }
}
