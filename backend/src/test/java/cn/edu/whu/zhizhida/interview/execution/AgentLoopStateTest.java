package cn.edu.whu.zhizhida.interview.execution;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class AgentLoopStateTest {
    private final JsonMapper json = new JsonMapper();

    @Test void repeatedEmptyQueriesIgnoreCaseWhitespaceAndNewIds() {
        var cp=json.createObjectNode();
        var empty=json.readTree("{\"status\":\"EMPTY\"}");
        var one=AgentLoopState.observe(json,cp,"search_knowledge",json.readTree("{\"query\":\" Redis  Retry \",\"topK\":1}"),empty);
        var two=AgentLoopState.observe(json,cp,"search_knowledge",json.readTree("{\"query\":\"redis retry\",\"topK\":6}"),empty);
        assertThat(one.stopReason()).isNull();
        assertThat(two.actionKey()).isEqualTo(one.actionKey());
        assertThat(two.stopReason()).isEqualTo("NO_PROGRESS");
        assertThat(two.decision()).isEqualTo("REVISE");
    }

    @Test void aDifferentQueryAloneDoesNotCountAsEvidence() {
        var cp=json.createObjectNode();
        var args=json.readTree("{\"query\":\"a\",\"topK\":2}");
        AgentLoopState.observe(json,cp,"search_knowledge",args,json.readTree("{\"status\":\"EMPTY\"}"));
        var result=AgentLoopState.observe(json,cp,"search_knowledge",json.readTree("{\"query\":\"b\",\"topK\":2}"),json.readTree("{\"status\":\"EMPTY\"}"));
        assertThat(result.newEvidence()).isZero();
        assertThat(result.stopReason()).isEqualTo("NO_PROGRESS");
    }

    @Test void sameKnowledgeChunkAcrossQueriesCannotInventProgress() {
        var cp=json.createObjectNode();
        var found=json.readTree("{\"status\":\"FOUND\",\"references\":[{\"id\":\"doc\",\"version\":1,\"sha256\":\"hash\",\"excerpts\":[{\"chunkId\":\"c\",\"startLine\":1,\"endLine\":2,\"content\":\"database evidence\"}]}]}");
        var one=AgentLoopState.observe(json,cp,"search_knowledge",json.readTree("{\"query\":\"a\"}"),found);
        var two=AgentLoopState.observe(json,cp,"search_knowledge",json.readTree("{\"query\":\"b\"}"),found);
        assertThat(one.newEvidence()).isEqualTo(1);
        assertThat(two.newEvidence()).isZero();
        assertThat(two.stopReason()).isNull();
        var three=AgentLoopState.observe(json,cp,"search_knowledge",json.readTree("{\"query\":\"c\"}"),found);
        assertThat(three.stopReason()).isEqualTo("NO_PROGRESS");
    }

    @Test void progressAndEvidenceToolsResolveTheSameFrozenObservation() {
        var cp=json.createObjectNode();
        var first=AgentLoopState.observe(json,cp,"get_learning_progress",json.readTree("{\"competencyId\":\"x\"}"),
                json.readTree("{\"status\":\"FOUND\",\"items\":[{\"observationIds\":[\"o1\"]}]}"), true);
        var next=AgentLoopState.observe(json,cp,"find_practice_evidence",json.readTree("{\"competencyId\":\"x\"}"),
                json.readTree("{\"status\":\"FOUND\",\"items\":[{\"id\":\"o1\",\"revision\":1}]}"), true);
        assertThat(first.newEvidence()).isEqualTo(1);
        assertThat(next.newEvidence()).isZero();
        assertThat(next.decision()).isEqualTo("VERIFY");
    }

    @Test void restoredStateRetainsFingerprintAndNoProgressStreak() {
        var cp=json.createObjectNode();
        var args=json.readTree("{\"answerId\":\"answer-1\",\"maxChars\":20}");
        var found=json.readTree("{\"status\":\"FOUND\",\"content\":\"回答🙂\",\"sha256\":\"version\"}");
        var first=AgentLoopState.observe(json,cp,"read_answer_evidence",args,found);
        var restored=(tools.jackson.databind.node.ObjectNode)json.readTree(cp.toString());
        var next=AgentLoopState.observe(json,restored,"read_answer_evidence",args,found);
        assertThat(next.before()).isEqualTo(first.after());
        assertThat(next.newEvidence()).isZero();
        assertThat(next.stopReason()).isEqualTo("NO_PROGRESS");
        assertThat(next.sourceKeys()).doesNotContain("回答");
    }

    @Test void missingOrUnsupportedStatusNeverCreatesEvidence() {
        var cp=json.createObjectNode();
        var result=AgentLoopState.observe(json,cp,"read_material_section",json.readTree("{\"sectionId\":\"x\"}"),
                json.readTree("{\"status\":\"invented\",\"content\":\"unverified\"}"));
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT");
        assertThat(result.newEvidence()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"B0","S1","S3"})
    void policiesShareComputeBudgetsAndReserveFinalOutput(String name) {
        var policy=BudgetProfile.production(name).execution();
        assertThat(policy.maxModelAttempts()).isEqualTo(4);
        assertThat(policy.maxToolBatches()).isEqualTo(2);
        assertThat(policy.maxToolExecutions()).isEqualTo(3);
        assertThat(policy.maxRepairs()).isEqualTo(1);
        assertThat(policy.deadlineMillis()).isEqualTo(90000);
        assertThat(policy.callMillis()).isEqualTo(25000);
        assertThat(policy.boundary(0,0,0,0,12000)).isNull();
        assertThat(policy.boundary(0,3,0,0,90000)).isEqualTo("BUDGET_LIMIT");
        assertThat(policy.boundary(0,0,2,0,90000)).isEqualTo("BUDGET_LIMIT");
        assertThat(policy.boundary(0,0,0,3,90000)).isEqualTo("BUDGET_LIMIT");
        assertThat(policy.boundary(0,0,0,0,11999)).isEqualTo("BUDGET_LIMIT");
        assertThat(policy.boundary(policy.maxSteps(),0,0,0,90000)).isEqualTo(policy.explicit()?"STEP_LIMIT":null);
    }

    @Test void unknownPoliciesAreRejectedAndOldConfigRemainsB0() {
        assertThatThrownBy(() -> BudgetProfile.production("S5")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AgentLoopState.policy(json.createObjectNode())).isEqualTo("B0");
    }
}
