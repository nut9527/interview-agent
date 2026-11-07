package cn.edu.whu.zhizhida.learning.api;

import java.time.Instant;
import java.util.List;

/** 供可信业务调用方按账号读取历史观察；命令只固定标识和修订号，不额外复制个人证据正文。 */
public interface PracticeMemoryAccess {
    String VERSION = "practice-memory-v1";
    record Reference(String id, long revision, String sourceSessionId, String sourceVersion) { }
    record Selection(String version, String executionMode, Instant since, List<String> competencies,
            List<Reference> references) {
        public Selection { competencies=List.copyOf(competencies); references=List.copyOf(references); }
        public static Selection none() { return new Selection(VERSION,"",Instant.EPOCH,List.of(),List.of()); }
        public boolean available() { return !references.isEmpty(); }
    }
    /** 按能力与执行模式固定可用引用，并排除当前会话；功能或个性化未开启、无匹配记录时返回空选择。 */
    Selection freeze(long owner, List<String> competencies, String executionMode, String excludedSession);
    /** 复用回执或恢复检查点时重新核对权限与修订；任一引用不再可用即拒绝整次读取，不静默替换。 */
    List<LearningMemory.Observation> read(long owner, Selection selection);
    /** 必须已有事务；复核同一选择并持锁至调用方提交，失效时拒绝整次提交，此事务内不得调用模型。 */
    void lockForCommit(long owner, Selection selection);
}
