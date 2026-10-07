package com.neocat.query.domain.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocat.common.error.exception.ValidationException;
import java.util.*;

/** Strict external input: malformed filters are not silently interpreted as a total query. */
@org.springframework.modulith.NamedInterface("query")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class MetricFilters {
    private final Map<String, Set<String>> values;

    public MetricFilters(Map<String, Set<String>> values) {
        this.values = values;
    }
    public static MetricFilters parse(String raw, ObjectMapper json) {
        if (raw == null || raw.isBlank()) return new MetricFilters(Map.of());
        if (raw.length() > 16384) throw invalid();
        try {
            com.fasterxml.jackson.databind.JsonNode root = json.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (!root.isObject() || root.size() > 32) throw invalid();
            Map<String, Set<String>> result = new TreeMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().isEmpty() || !field.getValue().isArray() || field.getValue().size() > 100) throw invalid();
                Set<String> values = new TreeSet<>();
                for (var value : field.getValue()) {
                    if (!value.isTextual()) throw invalid();
                    values.add(value.textValue());
                }
                if (!values.isEmpty()) result.put(field.getKey(), Set.copyOf(values));
            }
            return new MetricFilters(Map.copyOf(result));
        } catch (ValidationException e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }
    private static ValidationException invalid() { return new ValidationException(com.neocat.common.error.ErrorCode.INVALID_PARAM, "filters 必须为标签键到字符串数组的 JSON 对象"); }
    public boolean matches(Map<String, String> labels) {
        return values.entrySet().stream().allMatch(e -> labels.containsKey(e.getKey()) && e.getValue().contains(labels.get(e.getKey())));
    }
    public boolean total() { return values.isEmpty(); }
}
