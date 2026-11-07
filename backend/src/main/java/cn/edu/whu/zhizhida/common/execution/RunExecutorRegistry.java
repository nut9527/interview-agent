package cn.edu.whu.zhizhida.common.execution;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** 按业务主体分派运行；注册检查发生在单例装配后，避免提前解析执行器的运行端口依赖。 */
@Component
public final class RunExecutorRegistry implements SmartInitializingSingleton {
    private final ObjectProvider<RunExecutor> candidates;
    private volatile Map<String, RunExecutor> executors = Map.of();

    public RunExecutorRegistry(ObjectProvider<RunExecutor> candidates) {
        this.candidates = candidates;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var registered = new LinkedHashMap<String, RunExecutor>();
        candidates.stream().forEach(executor -> {
            String type = executor.subjectType();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("RUN_SUBJECT_TYPE_INVALID");
            }
            if (registered.putIfAbsent(type, executor) != null) {
                throw new IllegalStateException("RUN_EXECUTOR_DUPLICATE: " + type);
            }
        });
        executors = Map.copyOf(registered);
    }

    public RunExecutor require(String type) {
        RunExecutor executor = type == null ? null : executors.get(type);
        if (executor == null) {
            throw new RunFailureException("RUN_SUBJECT_UNSUPPORTED");
        }
        return executor;
    }
}
