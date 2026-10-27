package cn.edu.whu.zhizhida;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaRuntimeBaselineTest {
    @Test
    void applicationAndTestsUseJava21BytecodeAndRuntime() throws Exception {
        assertThat(Runtime.version().feature()).isEqualTo(21);
        for (Class<?> type : List.of(ZhizhidaApplication.class, JavaRuntimeBaselineTest.class)) {
            try (var input = new DataInputStream(type.getResourceAsStream(type.getSimpleName() + ".class"))) {
                assertThat(input.readInt()).isEqualTo(0xCAFEBABE);
                input.readUnsignedShort(); // minor version
                assertThat(input.readUnsignedShort()).as(type.getName()).isEqualTo(65);
            }
        }
    }
}
