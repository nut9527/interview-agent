package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TrainingKnowledgeContextTest {
    @Test void modelReceivesOnlyThisRoundsSourcesAndEmptySelectionDoesNotReuseOpeningExcerpts() {
        var json=new JsonMapper(); var policy=new InterviewAgentPolicy(); var prompts = new InterviewPromptBuilder(json);
        var first=new KnowledgeSnapshot.Excerpt("doc:1","TTL",1,1,"启动时 TTL 90 秒");
        var late=new KnowledgeSnapshot.Excerpt("doc:9","写后读",90,90,"本次请求读主库");
        var doc=new KnowledgeSnapshot("doc","旧标题","笔记",1,"hash","完整正文不应发送到模型",List.of(first));
        var state=policy.trainingPlan(new InterviewTraining(InterviewTraining.Topic.DATABASE,null,null,List.of(doc)),
                new InterviewPlanningContext("",List.of(),List.of(),List.of(),""),"model",List.of(),null);
        var reference=new KnowledgeSnapshot.Reference("doc","旧标题",1,"hash",List.of(late));
        for(var sources:List.of(List.of(reference),List.<KnowledgeSnapshot.Reference>of())) {
            var input = json.readTree(prompts.openingInput("专项",
                    InterviewAgentContext.encode("",state,policy.routing(state,0,3),false,InterviewDifficulty.STANDARD,sources), null).user());
                assertThat(input.path("untrustedEvidence").path("knowledgeReferences")).isEqualTo(json.valueToTree(sources));
                assertThat(input.toString()).doesNotContain("完整正文不应发送到模型","启动时 TTL 90 秒");

        }
    }
}
