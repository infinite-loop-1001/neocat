package com.neocat.alert.domain.engine

import com.neocat.alert.domain.rule.AlertChannel
import com.neocat.alert.domain.rule.AlertRule
import com.neocat.alert.domain.rule.AlertScope
import com.neocat.alert.domain.rule.AlertTarget
import com.neocat.alert.domain.rule.Combinator
import com.neocat.alert.domain.rule.Comparator
import com.neocat.alert.domain.rule.Condition

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

class NotificationSpec extends Specification {
    static final long T = 1_790_000_000_000L

    ChannelAvailability availability = Mock()
    DeliveryLogger logger = Mock()
    Notifier notifier = Mock()
    NotificationDispatcher dispatcher = new NotificationDispatcher(availability, logger, notifier)

    AlertRule rule(List<AlertChannel> channels, List<Long> recipients = [1L]) {
        new AlertRule(1L, AlertScope.SERVICE, null, '订单失败率', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                Combinator.AND, 1, [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05)],
                recipients, channels, true, false, T)
    }

    def 'unconfigured channels are rejected before saving the rule'() {
        when:
        dispatcher.validateChannels([AlertChannel.EMAIL, AlertChannel.DINGTALK])

        then:
        1 * availability.available(AlertChannel.EMAIL) >> true
        1 * availability.available(AlertChannel.DINGTALK) >> false
        thrown(IllegalArgumentException)
    }

    def 'enabled channels can be selected'() {
        when:
        dispatcher.validateChannels([AlertChannel.EMAIL, AlertChannel.DINGTALK])

        then:
        1 * availability.available(AlertChannel.EMAIL) >> true
        1 * availability.available(AlertChannel.DINGTALK) >> true
        noExceptionThrown()
    }

    def 'selectable channels are filtered by platform availability'() {
        when:
        def channels = dispatcher.selectableChannels()

        then:
        1 * availability.available(AlertChannel.EMAIL) >> true
        1 * availability.available(AlertChannel.DINGTALK) >> false
        1 * availability.available(AlertChannel.FEISHU) >> false
        channels == [AlertChannel.EMAIL]
    }

    def 'only effective recipients trigger a notification'() {
        when:
        def sent = dispatcher.dispatch(rule([AlertChannel.EMAIL]), [], T)

        then:
        sent.isEmpty()
        0 * notifier._
    }

    def 'each channel is dispatched once to all effective recipients'() {
        when:
        def sent = dispatcher.dispatch(rule([AlertChannel.EMAIL, AlertChannel.DINGTALK]), [1L, 2L], T)

        then:
        1 * availability.available(AlertChannel.EMAIL) >> true
        1 * availability.available(AlertChannel.DINGTALK) >> true
        1 * notifier.send({ it.getChannel() == AlertChannel.EMAIL && it.getRecipients() == [1L, 2L] })
        1 * notifier.send({ it.getChannel() == AlertChannel.DINGTALK && it.getRecipients() == [1L, 2L] })
        sent*.getChannel() == [AlertChannel.EMAIL, AlertChannel.DINGTALK]
    }

    def 'failed external delivery is logged and not counted as sent'() {
        given:
        def failure = new UnsupportedOperationException('not configured')

        when:
        def sent = dispatcher.dispatch(rule([AlertChannel.EMAIL, AlertChannel.DINGTALK]), [1L], T)

        then:
        1 * availability.available(AlertChannel.EMAIL) >> true
        1 * availability.available(AlertChannel.DINGTALK) >> true
        1 * notifier.send({ it.getChannel() == AlertChannel.EMAIL }) >> { throw failure }
        1 * logger.failed(1L, AlertChannel.EMAIL, failure, _ as String)
        1 * notifier.send({ it.getChannel() == AlertChannel.DINGTALK })
        sent*.getChannel() == [AlertChannel.DINGTALK]
    }

    def 'notification has no persisted history or in-app channel'() {
        expect:
        AlertChannel.values()*.name() as Set == ['EMAIL', 'DINGTALK', 'FEISHU'] as Set
        !NotificationDispatcher.declaredMethods*.name.any { it.toLowerCase().contains('history') }
    }
}
