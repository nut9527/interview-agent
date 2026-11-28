package cn.edu.whu.zhizhida.interview.execution;

import java.time.Instant;

import cn.edu.whu.zhizhida.messaging.RocketTransport;
import cn.edu.whu.zhizhida.messaging.RocketTransport.Delivery;

/** 延迟重试沿用原事件 ID 和消息体，使重复投递仍指向同一任务。 */
public class RunDispatchRetry {
    private final RocketTransport transport;

    public RunDispatchRetry(RocketTransport transport) {
        this.transport = transport;
    }

    public void publish(Delivery message) {
        transport.sendDelayed(
                "run-delay",
                message.keys().getFirst(),
                message.body(),
                Instant.now().plusSeconds(2));
    }
}
