package com.neocat.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.neocat.common.error.ErrorCode
import com.neocat.common.error.exception.BusinessRuleException
import com.neocat.common.error.exception.ConflictException
import com.neocat.common.http.error.ApiExceptionHandler
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.Unroll

class ApiExceptionHandlerLoggingSpec extends Specification {
    Logger logger

    Level previousLevel

    boolean previousAdditive

    ListAppender<ILoggingEvent> appender

    def setup() {
        logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler)
        previousLevel = logger.level
        previousAdditive = logger.additive
        appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.WARN
        logger.additive = false
    }

    def cleanup() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
        logger.additive = previousAdditive
    }

    def "未知异常记录 ERROR 及原始堆栈和 cause，响应不泄露内部细节"() {
        given:
        def cause = new IllegalStateException('offline database failure')
        def error = new RuntimeException('internal diagnostic detail', cause)

        when:
        def response = new ApiExceptionHandler().handleUnexpected(error)

        then:
        response.statusCode.value() == 500
        response.body.code == ErrorCode.INTERNAL_ERROR.code()
        response.body.message == '服务内部错误'
        appender.list.size() == 1
        def event = appender.list.first()
        event.level == Level.ERROR
        event.formattedMessage.contains('status=500')
        event.formattedMessage.contains('code=10004')
        event.throwableProxy.className == RuntimeException.name
        event.throwableProxy.message == error.message
        event.throwableProxy.stackTraceElementProxyArray.length > 0
        event.throwableProxy.cause.className == IllegalStateException.name
        event.throwableProxy.cause.message == cause.message
    }

    def "业务 5xx 同样记录 ERROR 堆栈并保留响应契约"() {
        given:
        def error = new BusinessRuleException(ErrorCode.INTERNAL_ERROR)

        when:
        def response = new ApiExceptionHandler().handleBusiness(error)

        then:
        response.statusCode.value() == 500
        response.body.code == ErrorCode.INTERNAL_ERROR.code()
        response.body.message == error.message
        appender.list.size() == 1
        appender.list.first().level == Level.ERROR
        appender.list.first().throwableProxy.className == BusinessRuleException.name
    }

    def "业务 4xx 仅记录 WARN 错误码和类型，不记录带参数的异常消息或堆栈"() {
        given:
        def error = new ConflictException(ErrorCode.USER_EXISTS, 'sensitive-input-marker')

        when:
        def response = new ApiExceptionHandler().handleBusiness(error)

        then:
        response.statusCode.value() == 409
        response.body.code == ErrorCode.USER_EXISTS.code()
        response.body.message == error.message
        appender.list.size() == 1
        def event = appender.list.first()
        event.level == Level.WARN
        event.formattedMessage.contains('status=409')
        event.formattedMessage.contains("code=${ErrorCode.USER_EXISTS.code()}")
        event.formattedMessage.contains(ConflictException.name)
        !event.formattedMessage.contains('sensitive-input-marker')
        !event.throwableProxy
    }

    @Unroll
    def "框架异常 #error.class.simpleName 保留 #status 响应并记录不含原始消息的 WARN"() {
        when:
        def handler = new ApiExceptionHandler()
        def response = notFound
                ? handler.handleNotFound((NoSuchElementException) error)
                : handler.handleBadRequest(error)

        then:
        response.statusCode.value() == status
        response.body.code == code.code()
        response.body.message == message
        appender.list.size() == 1
        def event = appender.list.first()
        event.level == Level.WARN
        event.formattedMessage.contains("status=${status}")
        event.formattedMessage.contains("code=${code.code()}")
        event.formattedMessage.contains(error.class.name)
        !event.formattedMessage.contains('sensitive-input-marker')
        !event.throwableProxy

        where:
        error                                                  | notFound | status | code                    | message
        new IllegalArgumentException('sensitive-input-marker')  | false    | 400    | ErrorCode.INVALID_PARAM | ErrorCode.INVALID_PARAM.message('sensitive-input-marker')
        new IllegalStateException('sensitive-input-marker')     | false    | 400    | ErrorCode.INVALID_PARAM | ErrorCode.INVALID_PARAM.message('sensitive-input-marker')
        new IllegalArgumentException()                         | false    | 400    | ErrorCode.INVALID_PARAM | ErrorCode.INVALID_PARAM.message('请求参数')
        new NoSuchElementException('sensitive-input-marker')   | true     | 404    | ErrorCode.NOT_FOUND     | ErrorCode.NOT_FOUND.message('sensitive-input-marker')
        new NoSuchElementException()                           | true     | 404    | ErrorCode.NOT_FOUND     | ErrorCode.NOT_FOUND.message('资源')
    }
}
