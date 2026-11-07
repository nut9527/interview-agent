package cn.edu.whu.zhizhida.interview.execution;

import java.time.Instant;
import java.util.UUID;

/** 消息仅为执行提示；身份、输入、租约与预算一律重新从数据库读取。 */
public record RunDispatchMessage(int schemaVersion, String eventId, String eventType,
        String runId, long dispatchSeq, Instant occurredAt) {
    public void validate() {
        if(schemaVersion!=1 || !"AGENT_RUN_READY".equals(eventType) || dispatchSeq<0 || occurredAt==null)
            throw new IllegalArgumentException("MESSAGE_SCHEMA");
        if(!UUID.fromString(eventId).toString().equals(eventId) || !UUID.fromString(runId).toString().equals(runId))
            throw new IllegalArgumentException("MESSAGE_ID");
    }
}
