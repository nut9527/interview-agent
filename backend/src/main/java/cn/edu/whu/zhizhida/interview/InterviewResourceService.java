package cn.edu.whu.zhizhida.interview;

import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.api.InterviewResources;
import cn.edu.whu.zhizhida.interview.api.InterviewService;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.KnowledgeCatalog;
import cn.edu.whu.zhizhida.interview.api.QuestionCatalog;

/** 面试结束后按各能力的最新观察关联复习资料与题目；来源已撤销时不再生成推荐。 */
@Service
public class InterviewResourceService {
    private final InterviewService interviews;
    private final KnowledgeCatalog knowledge;
    private final QuestionCatalog questions;

    public InterviewResourceService(InterviewService interviews, KnowledgeCatalog knowledge,
            QuestionCatalog questions) {
        this.interviews = interviews;
        this.knowledge = knowledge;
        this.questions = questions;
    }

    public List<InterviewResources> resources(String id) {
        var session = interviews.findSession(id);
        if (Boolean.TRUE.equals(session.sourceRevoked()))
            return List.of();
        if (session.state() != InterviewState.FINISHED)
            throw new UserInputException("面试结束后才可查看薄弱点关联资料。");
        if (session.agent() == null)
            return List.of();
        var latest = new LinkedHashMap<String, InterviewAgentState.Evaluation>();
        session.agent().evaluations().forEach(e -> latest.put(e.competencyId(), e));
        return latest.values().stream().filter(e -> e.decision().level() != InterviewAgentState.Level.STRONG).map(e -> {
            var name = session.agent().plan().stream().filter(c -> c.id().equals(e.competencyId())).findFirst()
                    .orElseThrow().name();
            String query = name + " " + e.decision().gap().replace("需要进一步核对", " ").replace("的事实与验证依据", " ");
            if (query.length() > 300)
                query = query.substring(0, 300);
            return new InterviewResources(e.round(), name, e.decision().gap(), knowledge.recommend(query),
                    questions.recommend(e.competencyId(), query));
        }).toList();
    }
}
