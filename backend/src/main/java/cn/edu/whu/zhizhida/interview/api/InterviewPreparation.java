package cn.edu.whu.zhizhida.interview.api;

import cn.edu.whu.zhizhida.jobs.api.TargetJob;
import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 固定来源与项目快照，后续追问建议随最终回答一同提交。 */
public record InterviewPreparation(String assessmentId, JsonNode diagnostic, String projectField,
                                   ResumeDetails.Project projectSnapshot, TargetJob targetJobSnapshot,
                                   List<String> topics, List<FollowUp> followUps) {
    public InterviewPreparation {
        topics = topics == null ? List.of() : List.copyOf(topics);
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
    }
    public record FollowUp(int turn, String question, String answer, String feedback) {}

    public InterviewPreparation withFollowUps(List<FollowUp> items) {
        return new InterviewPreparation(assessmentId, diagnostic, projectField, projectSnapshot,
                targetJobSnapshot, topics, List.copyOf(items));
    }
}
