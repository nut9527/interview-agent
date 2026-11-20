package cn.edu.whu.zhizhida.interview;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.interview.api.InterviewAgentState;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.interview.api.InterviewExperience;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.api.InterviewState;
import cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory;

@Repository
public class QuestionPracticeRepository {
        private final JdbcTemplate jdbc;
        private final JsonColumnCodec json;

        public QuestionPracticeRepository(JdbcTemplate jdbc, JsonColumnCodec json) {
                this.jdbc = jdbc;
                this.json = json;
        }

        public List<QuestionPracticeHistory.Progress> progress(long userId) {
                return jdbc.query(
                                """
                                                SELECT question_bank_id,session_id,updated_at,agent_json,state,experience_json,attempts FROM (
                                                  SELECT question_bank_id,session_id,updated_at,agent_json,state,experience_json,
                                                    COUNT(*) OVER (PARTITION BY question_bank_id) AS attempts,
                                                    ROW_NUMBER() OVER (PARTITION BY question_bank_id ORDER BY created_at DESC,session_id DESC) AS n
                                                  FROM interview_sessions WHERE user_id=? AND question_bank_id IS NOT NULL AND answered_count>0
                                                ) p WHERE n=1 ORDER BY question_bank_id
                                                """,
                                (rs, n) -> {
                                        var agent = json.read(rs.getString("agent_json"), InterviewAgentState.class);
                                        var evaluations = hidden(rs.getString("experience_json"), rs.getString("state"))
                                                        ? List.<InterviewAgentState.Evaluation>of()
                                                        : agent.evaluations();
                                        var status = !evaluations.isEmpty()
                                                        && evaluations.get(evaluations.size() - 1).decision()
                                                                        .level() != InterviewAgentState.Level.STRONG
                                                                                        ? QuestionPracticeHistory.Status.NEEDS_REVIEW
                                                                                        : QuestionPracticeHistory.Status.PRACTICED;
                                        return new QuestionPracticeHistory.Progress(rs.getString("question_bank_id"),
                                                        status,
                                                        rs.getLong("attempts"),
                                                        rs.getString("session_id"),
                                                        rs.getTimestamp("updated_at").toInstant());
                                }, userId);
        }

        public PageResult<QuestionPracticeHistory.Practice> history(long userId, String id, PageRequest page) {
                long count = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM interview_sessions WHERE user_id=? AND question_bank_id=?",
                                Long.class, userId,
                                id);
                var rows = jdbc.query(
                                """
                                                SELECT session_id,agent_json,turns_json,difficulty,state,experience_json,created_at,updated_at FROM interview_sessions
                                                WHERE user_id=? AND question_bank_id=? ORDER BY created_at DESC,session_id DESC LIMIT ? OFFSET ?
                                                """,
                                (rs, n) -> {
                                        var agent = json.read(rs.getString("agent_json"), InterviewAgentState.class);
                                        var turns = json.read(rs.getString("turns_json"), InterviewTurn[].class);
                                        var rounds = new ArrayList<QuestionPracticeHistory.Round>();
                                        boolean hide = hidden(rs.getString("experience_json"), rs.getString("state"));
                                        String question = "";
                                        for (int i = 0; i < turns.length; i++) {
                                                if ("interviewer".equals(turns[i].speaker()))
                                                        question = turns[i].content();
                                                if (!"user".equals(turns[i].speaker()))
                                                        continue;
                                                int round = rounds.size() + 1;
                                                var evaluation = hide ? null
                                                                : agent.evaluations().stream()
                                                                                .filter(e -> e.round() == round)
                                                                                .findFirst().orElse(null);
                                                String feedback = !hide && i + 1 < turns.length
                                                                && "system".equals(turns[i + 1].speaker())
                                                                                ? turns[i + 1].content()
                                                                                : "";
                                                rounds.add(new QuestionPracticeHistory.Round(round, question,
                                                                turns[i].content(), feedback,
                                                                evaluation == null ? null
                                                                                : evaluation.decision().level(),
                                                                evaluation == null ? ""
                                                                                : evaluation.decision().evidence(),
                                                                evaluation == null ? "" : evaluation.decision().gap()));
                                        }
                                        return new QuestionPracticeHistory.Practice(rs.getString("session_id"),
                                                        hide ? agent.training().question().withoutHints()
                                                                        : agent.training().question(),
                                                        InterviewDifficulty.valueOf(rs.getString("difficulty")),
                                                        InterviewState.valueOf(rs.getString("state")),
                                                        rs.getTimestamp("created_at").toInstant(),
                                                        rs.getTimestamp("updated_at").toInstant(),
                                                        rounds);
                                }, userId, id, page.size(), page.offset());
                return new PageResult<>(rows, count, page);
        }

        private boolean hidden(String experience, String state) {
                return experience != null && !"FINISHED".equals(state)
                                && json.read(experience, InterviewExperience.class)
                                                .format() == InterviewExperience.Format.FORMAL;
        }
}
