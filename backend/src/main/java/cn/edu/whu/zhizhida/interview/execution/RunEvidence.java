package cn.edu.whu.zhizhida.interview.execution;

import java.util.List;

import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;

final class RunEvidence {
    private final InterviewRunStore store;
    private final InterviewEvidenceTools tools;

    RunEvidence(InterviewRunStore store, InterviewEvidenceTools tools) {
        this.store = store;
        this.tools = tools;
    }

    List<KnowledgeSnapshot.Reference> verifiedReviewSources(RunContext execution) {
        if (execution.pending() == null || execution.checkpoint().path("sources").isEmpty())
            return List.of();
        return tools.verifiedExcerpts(
                execution.session(), store.knowledgeReceipts(execution.claim()));
    }

    java.util.Map<String, EvidenceReview.Source> evidenceSources(RunContext execution) {
        var sources = new java.util.HashMap<String, EvidenceReview.Source>();
        int n = 0;
        for (var turn : execution.turns())
            if (turn.speaker().equals("user"))
                sources.put("answer-" + (++n), new EvidenceReview.Source(turn.content(), false));
        for (var section : tools.allSections(execution.session(), execution.materials()))
            sources.put(section.sectionId(), new EvidenceReview.Source(section.content(), false));
        sources.putAll(
                EvidenceReview.references(
                        execution.claim().owner(),
                        execution.session().agent().training(),
                        verifiedReviewSources(execution)));
        return sources;
    }
}
