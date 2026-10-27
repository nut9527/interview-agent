package cn.edu.whu.zhizhida.common.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonColumnCodec {

    private final ObjectMapper objectMapper;

    public JsonColumnCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库 JSON 序列化失败", exception);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库 JSON 反序列化失败", exception);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库 JSON 反序列化失败", exception);
        }
    }
}
