package com.neocat.query.api.http.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** 查询服务现有 Map 读模型到固定 HTTP DTO 的转换边界。 */
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
