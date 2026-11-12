package cn.edu.whu.zhizhida.common;

import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** sorted-json-v1：递归排序对象键，保留数组顺序和 JSON 标量原文。 */
public final class JsonFingerprint {
    private JsonFingerprint() {}

    public static String sha(JsonNode value) {
        return Sha256.hex(canonical(value).toString());
    }

    public static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> result.set(key, canonical(child)));
            return result;
        }
        if (value.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            value.forEach(child -> result.add(canonical(child)));
            return result;
        }
        return value;
    }
}
