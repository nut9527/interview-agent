package cn.edu.whu.zhizhida.interview.execution;

import java.util.List;

import cn.edu.whu.zhizhida.interview.api.InterviewSession;
import cn.edu.whu.zhizhida.interview.api.InterviewSession.InterviewTurn;
import cn.edu.whu.zhizhida.interview.domain.InterviewTransitions;
import cn.edu.whu.zhizhida.learning.api.PracticeMemoryAccess;
import tools.jackson.databind.node.ObjectNode;

record RunContext(
                InterviewRunStore.Claim claim,
                InterviewSession session,
                InterviewTransitions.PendingAnswer pending,
                boolean opening,
                PracticeMemoryAccess.Selection memorySelection,
                List<InterviewEvidenceTools.Section> materials,
                List<InterviewTurn> turns,
                String query,
                BudgetProfile budget,
                String context,
                ObjectNode checkpoint) {
}
