package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.InterviewTurn;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSessionTest {

    @Test
    void shouldExposeOwnedHistoricalSnapshotInApiPayload() throws Exception {
        InterviewSession session = new InterviewSession(
                "session-private",
                "Java 后端工程师",
                7L,
                "技能：Java；MySQL",
                InterviewState.WAITING_ANSWER,
                1,
                List.of(new InterviewTurn("interviewer", "请介绍项目"))
        );

        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(session);

        assertThat(json).contains("\"resumeId\":7");
        assertThat(json).contains("resumeSnapshot", "技能：Java；MySQL", "totalRounds");
    }
}
