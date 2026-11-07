package cn.edu.whu.zhizhida.interview.execution;

import java.util.List;
import cn.edu.whu.zhizhida.common.execution.ToolRegistry.Registration;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.learning.api.LearningMemory.Observation;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess.Selection;

/** 供应商仅接收服务端冻结且资格有效的上下文，不使用模型提供的身份。 */
public interface InterviewToolProvider {
    String MEMORY_SCOPE = "practice-memory";
    record Context(List<InterviewEvidenceTools.Section> sections, List<InterviewTurn> turns,
            List<KnowledgeSnapshot> knowledge, Selection memory, List<Observation> observations) {
        public Context {
            sections = List.copyOf(sections);
            turns = List.copyOf(turns);
            knowledge = List.copyOf(knowledge);
            observations = List.copyOf(observations);
            java.util.Objects.requireNonNull(memory);
        }
        static Context validation(Selection memory) {
            return new Context(List.of(), List.of(), List.of(), memory, List.of());
        }
    }
    List<Registration<Context, InterviewEvidenceTools.Result>> tools();
}
