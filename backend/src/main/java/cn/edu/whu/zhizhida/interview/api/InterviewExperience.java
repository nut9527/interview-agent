package cn.edu.whu.zhizhida.interview.api;

import java.time.Instant;

/** 展示规则独立于面试主题（InterviewMode）；计时仅作提示。 */
public record InterviewExperience(Format format, Integer timeLimitMinutes, Instant startedAt) {
    public enum Format { TEACHING, FORMAL }
    public InterviewExperience { format = format == null ? Format.TEACHING : format; }
}
