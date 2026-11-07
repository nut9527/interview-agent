package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.*;
import cn.edu.whu.zhizhida.interview.domain.InterviewAgentPolicy;
import cn.edu.whu.zhizhida.ai.api.JsonChatClient;
import cn.edu.whu.zhizhida.observability.BusinessMetrics;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TrainingKnowledgeContextTest {
    @Test void modelReceivesOnlyThisRoundsSourcesAndEmptySelectionDoesNotReuseOpeningExcerpts() {
        var json=new JsonMapper(); var policy=new InterviewAgentPolicy(); var client=mock(JsonChatClient.class);
        var first=new KnowledgeSnapshot.Excerpt("doc:1","TTL",1,1,"启动时 TTL 90 秒");
        var late=new KnowledgeSnapshot.Excerpt("doc:9","写后读",90,90,"本次请求读主库");
        var doc=new KnowledgeSnapshot("doc","旧标题","笔记",1,"hash","完整正文不应发送到模型",List.of(first));
        var state=policy.trainingPlan(new InterviewTraining(InterviewTraining.Topic.DATABASE,null,null,List.of(doc)),
                new InterviewPlanningContext("",List.of(),List.of(),List.of(),""),"model",List.of(),null);
        var reference=new KnowledgeSnapshot.Reference("doc","旧标题",1,"hash",List.of(late));
        for(var sources:List.of(List.of(reference),List.<KnowledgeSnapshot.Reference>of())) {
            doAnswer(call->{
                var input=json.readTree(call.getArgument(1,String.class));
                assertThat(input.path("untrustedEvidence").path("knowledgeReferences")).isEqualTo(json.valueToTree(sources));
                assertThat(input.toString()).doesNotContain("完整正文不应发送到模型","启动时 TTL 90 秒");
                return json.createObjectNode().put("question","请解释存储一致性及验证思路，并说明资料中结论的适用边界。 ").put("questionCompetencyId","storage");
            }).when(client).completeJson(anyString(),anyString(),anyDouble(),anyInt());
            new DefaultInterviewAiService(client,json,BusinessMetrics.disabled()).openingQuestion("专项",
                    InterviewAgentContext.encode("",state,policy.routing(state,0,3),false,InterviewDifficulty.STANDARD,sources));
        }
    }
}
