package com.neocat.query.api.http.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** 查询服务现有 Map 读模型到固定 HTTP DTO 的转换边界。 */
// rules: 全局使用一处反序列化/序列化静态方法, 不要单独通过 bean 的形式做 json 相关序列化/反序列化
@Component
public class ReportConvert {
    private final ObjectMapper json;

    public ReportConvert(ObjectMapper json) {
        this.json = json;
    }

    public <T> T response(Map<String, ?> model, Class<T> type) {
        return json.convertValue(model, type);
    }

    public <T> List<T> responses(List<? extends Map<String, ?>> models, Class<T> type) {
        return models.stream().map(model -> response(model, type)).toList();
    }
}
