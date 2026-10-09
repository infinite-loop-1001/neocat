package com.neocat.web

import com.neocat.common.http.error.ApiExceptionHandler
import com.neocat.common.http.error.ErrorCodeMapping
import com.neocat.common.error.ErrorCode
import com.neocat.common.error.exception.ConflictException
import com.neocat.common.error.exception.ResourceNotFoundException
import com.neocat.common.error.exception.AuthenticationException
import com.neocat.common.error.exception.AuthorizationException
import com.neocat.common.error.exception.ValidationException
import com.neocat.common.error.exception.ExpiredException
import com.neocat.common.error.exception.BusinessRuleException
import com.neocat.common.error.exception.IngestException
import com.neocat.ingest.infra.protocol.IngestResponseMapper
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import com.neocat.ingest.domain.receive.result.IngestResult

class ErrorModelSpec extends Specification {
    def "错误码分段且唯一，可反查"() {
        expect:
        ErrorCode.BAD_REQUEST.code() == 10001
        ErrorCode.BAD_CREDENTIALS.code() >= 20001
        ErrorCode.LEAF_HAS_RESOURCES.code() >= 30001
        ErrorCode.UNIT_MISMATCH.code() >= 40001
        ErrorCode.INVALID_RECIPIENT.code() >= 50001
        ErrorCode.TREE_EXPIRED.code() >= 60001
        ErrorCode.ALREADY_INITIALIZED.code() >= 70001
        ErrorCode.TRACE_EXPIRED.code() >= 80001
        ErrorCode.values()*.code().toSet().size() == ErrorCode.values().size()
        ErrorCode.values().every { ErrorCode.fromCode(it.code()) == it }
        ErrorCode.fromCode(99999) == null
        ErrorCode.values().every { it.code() % 10000 >= 1 && it.code() % 10000 <= 9999 }
        ErrorCode.values().every { ErrorCodeMapping.covers(it) }
    }

    def "每类错误使用其自己的编号区间，不依据 HTTP 状态确定区间"() {
        expect:
        ErrorCode.values().every { code ->
            def section = code.code().intdiv(10000)
            switch (section) {
                case 1 -> code.name() in ['BAD_REQUEST', 'INVALID_PARAM', 'NOT_FOUND', 'INTERNAL_ERROR']
                case 2 -> code.name() in ['UNAUTHENTICATED', 'BAD_CREDENTIALS', 'FORBIDDEN', 'PASSWORD_CHANGE_REQUIRED',
                                          'USER_EXISTS', 'USER_NOT_FOUND', 'PASSWORD_TOO_SHORT', 'PASSWORD_UNCHANGED', 'CANNOT_MODIFY_SELF']
                case 3 -> code.name() in ['HAS_CHILDREN', 'NAME_DUPLICATED', 'LEAF_HAS_RESOURCES', 'NOT_ORG_MEMBER',
                                          'ORG_NOT_FOUND', 'PARENT_ORG_NOT_FOUND', 'CONFIRM_NAME_MISMATCH']
                case 4 -> code.name() in ['NOT_LEAF', 'UNIT_MISMATCH', 'FORMULA_INVALID', 'TARGET_NOT_REFERENCED',
                                          'DASHBOARD_NOT_FOUND', 'CARD_NOT_FOUND', 'CARD_NOT_IN_DASHBOARD',
                                          'CARD_TARGET_REQUIRED', 'CARD_EVALUATION_UNAVAILABLE']
                case 5 -> code.name() in ['INVALID_RECIPIENT', 'CHANNEL_UNAVAILABLE', 'UNSUPPORTED_EXPRESSION', 'ALERT_ORG_REQUIRED']
                case 6 -> code.name() in ['UNSUPPORTED_VERSION', 'MALFORMED_TREE', 'TREE_TOO_LARGE',
                                          'BATCH_TOO_LARGE', 'TREE_EXPIRED', 'ID_CONFLICT']
                case 7 -> code.name() in ['ALREADY_INITIALIZED', 'NOT_INITIALIZED', 'TIMEZONE_IMMUTABLE']
                case 8 -> code.name() in ['TRACE_EXPIRED', 'TRACE_NOT_FOUND']
                default -> false
            }
        }
    }

    def "错误码不携带分类字段：类别只由异常子类表达"() {
        expect:
        !ErrorCode.declaredFields*.name.contains('kind')
        !ErrorCode.methods*.name.contains('kind')
    }

    def "异常的消息由模板填参生成，参数个数必须匹配"() {
        expect:
        new ConflictException(ErrorCode.USER_EXISTS, 'alice').message == '用户名已存在：alice'
        new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, 42).message == '账号不存在：42'
        new ResourceNotFoundException(ErrorCode.ORG_NOT_FOUND, 9999).message == '组织节点不存在：9999'
        new AuthenticationException(ErrorCode.BAD_CREDENTIALS).code() == ErrorCode.BAD_CREDENTIALS
        new AuthorizationException(ErrorCode.NOT_ORG_MEMBER).code() == ErrorCode.NOT_ORG_MEMBER
        new ValidationException(ErrorCode.UNIT_MISMATCH).code() == ErrorCode.UNIT_MISMATCH
        new ExpiredException(ErrorCode.TRACE_EXPIRED).code() == ErrorCode.TRACE_EXPIRED
        new BusinessRuleException(ErrorCode.LEAF_HAS_RESOURCES).code() == ErrorCode.LEAF_HAS_RESOURCES
        new IngestException(ErrorCode.MALFORMED_TREE, '请求体为空').message == '上报数据不合法：请求体为空'
        and: "参数被当作纯文本插入，不会被当作模板再次展开"
        new ConflictException(ErrorCode.USER_EXISTS, '{1}').message == '用户名已存在：{1}'

        when: "模板需要参数却未提供"
        new ConflictException(ErrorCode.USER_EXISTS)
        then:
        thrown(IllegalArgumentException)

        when: "提供了多余参数"
        new AuthenticationException(ErrorCode.BAD_CREDENTIALS, '多余')
        then:
        thrown(IllegalArgumentException)
    }

    def "REST 错误输出数字且保留模板消息"() {
        given:
        def response = new ApiExceptionHandler().handleBusiness(new ConflictException(ErrorCode.USER_EXISTS, 'alice'))
        def json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response.body))
        expect:
        response.statusCode.value() == 409
        json.get('code').isNumber()
        json.get('code').asInt() == ErrorCode.USER_EXISTS.code()
        json.get('message').asText() == '用户名已存在：alice'
    }

    def "上报异常输出数字字符串，正常结果码不变"() {
        expect:
        IngestResponseMapper.fromError(new IngestException(ErrorCode.MALFORMED_TREE, '请求体为空'), 1).code == '60002'
        IngestResponseMapper.toResponse(IngestResult.rejected('ID_CONFLICT', 1)).code == '60006'
        IngestResponseMapper.toResponse(IngestResult.accepted(1)).code == 'OK'
    }
}
