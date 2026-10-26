package cn.edu.whu.zhizhida.common.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonColumnCodecTest {

    private JsonColumnCodec codec;

    @BeforeEach
    void setUp() {
        codec = new JsonColumnCodec(new ObjectMapper());
    }

    @Test
    void shouldWriteSerializeObjectToJsonString() {
        record TestData(String name, int value) {}
        TestData data = new TestData("test", 42);

        String json = codec.write(data);

        assertThat(json).contains("\"name\"", "\"test\"", "\"value\"", "42");
    }

    @Test
    void shouldWriteSerializeListToJsonArray() {
        List<String> list = List.of("a", "b", "c");

        String json = codec.write(list);

        assertThat(json).isEqualTo("[\"a\",\"b\",\"c\"]");
    }

    @Test
    void shouldWriteSerializeMapToJsonObject() {
        Map<String, Integer> map = Map.of("key1", 1, "key2", 2);

        String json = codec.write(map);

        assertThat(json).contains("\"key1\"", "1", "\"key2\"", "2");
    }

    @Test
    void shouldWriteSerializeNull() {
        String json = codec.write(null);

        assertThat(json).isEqualTo("null");
    }

    @Test
    void shouldReadWithTypeReferenceDeserializeJson() {
        String json = "[\"Java\",\"Spring\",\"MySQL\"]";
        TypeReference<List<String>> typeRef = new TypeReference<>() {};

        List<String> result = codec.read(json, typeRef);

        assertThat(result).containsExactly("Java", "Spring", "MySQL");
    }

    @Test
    void shouldReadWithTypeReferenceDeserializeComplexType() {
        String json = "[{\"name\":\"a\",\"value\":1},{\"name\":\"b\",\"value\":2}]";
        record Item(String name, int value) {}
        TypeReference<List<Item>> typeRef = new TypeReference<>() {};

        List<Item> result = codec.read(json, typeRef);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("a");
        assertThat(result.get(1).value()).isEqualTo(2);
    }

    @Test
    void shouldReadWithClassDeserializeJson() {
        String json = "{\"name\":\"test\",\"value\":42}";
        record TestData(String name, int value) {}

        TestData result = codec.read(json, TestData.class);

        assertThat(result.name()).isEqualTo("test");
        assertThat(result.value()).isEqualTo(42);
    }

    @Test
    void shouldWriteThrowOnNonSerializableObject() {
        Object nonSerializable = new Object() {
            private final Object self = this; // 构造循环引用。

            public Object getSelf() {
                return self;
            }
        };

        assertThatThrownBy(() -> codec.write(nonSerializable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 序列化失败");
    }

    @Test
    void shouldReadThrowOnInvalidJsonWithTypeReference() {
        String invalidJson = "this is not json";
        TypeReference<List<String>> typeRef = new TypeReference<>() {};

        assertThatThrownBy(() -> codec.read(invalidJson, typeRef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 反序列化失败");
    }

    @Test
    void shouldReadThrowOnInvalidJsonWithClass() {
        String invalidJson = "{broken";

        assertThatThrownBy(() -> codec.read(invalidJson, String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 反序列化失败");
    }

    @Test
    void shouldWriteThrowWhenObjectMapperFails() throws JsonProcessingException {
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        when(mockMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("mock error") {});
        JsonColumnCodec mockCodec = new JsonColumnCodec(mockMapper);

        assertThatThrownBy(() -> mockCodec.write("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 序列化失败");
    }
}
