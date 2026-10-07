package com.neocat.analysis.domain.analyzer

import com.neocat.ingest.domain.tree.MessageTree
import com.neocat.ingest.domain.tree.IngestFixtures
import spock.lang.Specification

/**
 * G6 任务29（红）：分析扇出与单域隔离。
 * 对应 PRD 02 §9：七域扇出；单域失败不阻断其他域；记录失败域。
 */
class FanOutSpec extends Specification {

    static MessageTree tree() {
        IngestFixtures.simpleTree("m-1")
    }

    /** 可编程分析器：记录调用并可选抛异常。 */
    static class StubAnalyzer implements Analyzer {
        final String domainName
        final boolean failing
        int calls = 0
        List<MessageTree> seen = []

        StubAnalyzer(String domain, boolean failing = false) {
            this.domainName = domain
            this.failing = failing
        }

        @Override
        String domain() {
            domainName
        }

        @Override
        void analyze(MessageTree t) {
            calls++
            seen << t
            if (failing) {
                throw new IllegalStateException("域 $domainName 分析失败")
            }
        }
    }

    def "一棵树扇出到全部处理域"() {
        given:
        def domains = ["transaction", "event", "problem", "heartbeat", "metric", "dependency", "trace"]
        def analyzers = domains.collect { new StubAnalyzer(it) }
        def consumer = new RealtimeConsumer(analyzers)

        when:
        def result = consumer.consume(tree())

        then:
        result.getSucceededDomains().sort() == domains.sort()
        result.getFailures().isEmpty()
        analyzers.every { it.calls == 1 }
    }

    def "单域失败不阻断其他域：同一树仍送达全部其他域"() {
        given:
        def tx = new StubAnalyzer("transaction")
        def broken = new StubAnalyzer("event", true)
        def problem = new StubAnalyzer("problem")
        def consumer = new RealtimeConsumer([tx, broken, problem])

        when:
        def result = consumer.consume(tree())

        then:
        tx.calls == 1
        problem.calls == 1
        broken.calls == 1

        and: "只有失败域被记为失败"
        result.getFailures()*.getDomain() == ["event"]
        result.getSucceededDomains().sort() == ["problem", "transaction"]
    }

    def "失败记录包含失败域、MessageTree ID 与原因"() {
        given:
        def consumer = new RealtimeConsumer([new StubAnalyzer("metric", true)])

        when:
        def result = consumer.consume(tree())

        then:
        def failure = result.getFailures()[0]
        failure.getDomain() == "metric"
        failure.getMessageId() == "m-1"
        failure.getReason().contains("域 metric 分析失败")
    }

    def "多个域同时失败时全部被记录，其余域继续"() {
        given:
        def ok1 = new StubAnalyzer("transaction")
        def bad1 = new StubAnalyzer("event", true)
        def bad2 = new StubAnalyzer("metric", true)
        def ok2 = new StubAnalyzer("trace")
        def consumer = new RealtimeConsumer([ok1, bad1, bad2, ok2])

        when:
        def result = consumer.consume(tree())

        then:
        result.getFailures()*.getDomain() == ["event", "metric"]
        result.getSucceededDomains().sort() == ["trace", "transaction"]
        ok1.calls == 1 && ok2.calls == 1
    }

    def "失败域抛出生异常（Error）时同样不阻断其他域"() {
        given:
        def exploding = new Analyzer() {
            @Override
            String domain() { "heartbeat" }

            @Override
            void analyze(MessageTree t) { throw new AssertionError("模拟严重错误") }
        }
        def ok = new StubAnalyzer("transaction")
        def consumer = new RealtimeConsumer([exploding, ok])

        when:
        def result = consumer.consume(tree())

        then:
        ok.calls == 1
        result.getFailures()*.getDomain() == ["heartbeat"]
    }

    def "没有任何分析器时不报错且无失败"() {
        given:
        def consumer = new RealtimeConsumer([])

        when:
        def result = consumer.consume(tree())

        then:
        result.getSucceededDomains().isEmpty()
        !result.anyFailed()
    }

    def "所有域都失败时结果标记为存在失败"() {
        given:
        def consumer = new RealtimeConsumer([new StubAnalyzer("a", true), new StubAnalyzer("b", true)])

        when:
        def result = consumer.consume(tree())

        then:
        result.anyFailed()
        result.getFailures().size() == 2
        result.getSucceededDomains().isEmpty()
    }

    def "同一实例可重复消费不同树（消费者循环）"() {
        given:
        def tx = new StubAnalyzer("transaction")
        def consumer = new RealtimeConsumer([tx])

        when:
        consumer.consume(IngestFixtures.simpleTree("m-1"))
        consumer.consume(IngestFixtures.simpleTree("m-2"))

        then:
        tx.calls == 2
        tx.seen*.getMessageId() == ["m-1", "m-2"]
    }
}
