package cn.edu.whu.zhizhida.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 对模型返回的非可信 JSON 执行字段类型、长度和集合大小校验。
 * 不将数字或对象隐式转为字符串，避免格式错误的响应被当成业务成功。
 */
public final class StructuredOutput {
    private StructuredOutput() { }

    public static String text(JsonNode payload, String field, int maxLength) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || !node.isTextual()) throw invalid(field);
        String value = node.textValue().trim();
        if (value.isBlank() || value.length() > maxLength) throw invalid(field);
        return value;
    }

    public static JsonNode array(JsonNode payload, String field, int maxSize) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || !node.isArray() || node.size() > maxSize) throw invalid(field);
        return node;
    }

    public static List<String> texts(JsonNode payload, String field, int maxSize, int maxLength) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : array(payload, field, maxSize)) {
            if (!node.isTextual() || node.textValue().isBlank() || node.textValue().trim().length() > maxLength) {
                throw invalid(field);
            }
            result.add(node.textValue().trim());
        }
        return List.copyOf(result);
    }

    public static IllegalStateException invalid(String field) {
        return new FieldValidationException(field);
    }

    public static final class FieldValidationException extends IllegalStateException {
        private FieldValidationException(String field) { super("模型业务响应字段 " + field + " 缺失或格式、长度不合法，请重试。"); }
    }
}
