package cn.edu.whu.zhizhida.learning.api;

import java.util.List;
import java.util.Optional;

/** 由学习模块定义的证据来源接口；身份由调用方提供，不能来自模型参数。 */
public interface PracticeEvidenceSource {
    record SourceRef(long owner, String sessionId, String version) { }
    record Snapshot(SourceRef source, List<LearningMemory.Fact> facts) {
        public Snapshot { facts = List.copyOf(facts); }
    }
    /** 必须已有事务；锁定本人来源，缺失、未完成或授权已失效时返回 Optional.empty()，并发撤销也可能抛出来源失效异常。 */
    Optional<Snapshot> lock(long owner, String sessionId);
    /** 仅供后台跨账号对账，按会话标识游标扫描；结果不代表当前授权，处理前必须再次调用 lock。 */
    List<SourceRef> scan(String afterSessionId, int limit);
}
