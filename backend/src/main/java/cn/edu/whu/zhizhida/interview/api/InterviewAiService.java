package cn.edu.whu.zhizhida.interview.api;

import cn.edu.whu.zhizhida.interview.api.InterviewTurn;

import java.util.List;

public interface InterviewAiService {

    default String executionMode() { return "model"; }
    default boolean supportsAgentDecisions() { return false; }
    default boolean supportsReviewRepair() { return false; }
    default boolean supportsOpeningRepair() { return false; }

    default String repairOpening(String targetRole, String context, String validationReason) {
        throw new UnsupportedOperationException("Interview opening repair is not supported");
    }

    default InterviewAiReply repairAnswer(String targetRole, String context, List<InterviewTurn> transcript,
                                          int answeredCount, boolean finalRound, String validationReason) {
        throw new UnsupportedOperationException("Interview review repair is not supported");
    }

    String openingQuestion(String targetRole, String resumeSnapshot);

    InterviewAiReply reviewAnswer(String targetRole,
                                  String resumeSnapshot,
                                  List<InterviewTurn> transcript,
                                  int answeredCount,
                                  boolean finalRound);
}
