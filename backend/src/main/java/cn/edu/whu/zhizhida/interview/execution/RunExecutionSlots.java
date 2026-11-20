package cn.edu.whu.zhizhida.interview.execution;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本实例在派发或领取租约前取得许可，同一用户最多占一席；跨实例容量另由数据库准入控制。 */
@Component
public class RunExecutionSlots {
    private final int limit;
    private final Map<String, Long> active = new HashMap<>();

    public RunExecutionSlots(@Value("${interview.runs.capacity.max-active:4}") int limit) {
        if (limit < 1 || limit > 64)
            throw new IllegalArgumentException("Invalid execution capacity");
        this.limit = limit;
    }

    /** 无空位、运行重复或同用户已在执行时返回 null；取得的许可必须在执行结束或派发失败时关闭。 */
    public synchronized Permit tryAcquire(String id, long owner) {
        if (active.size() >= limit || active.containsKey(id) || active.containsValue(owner))
            return null;
        active.put(id, owner);
        return new Permit(id);
    }

    public final class Permit implements AutoCloseable {
        private final String id;
        private boolean closed;

        private Permit(String id) {
            this.id = id;
        }

        @Override
        public void close() {
            synchronized (RunExecutionSlots.this) {
                if (!closed) {
                    active.remove(id);
                    closed = true;
                }
            }
        }
    }
}
