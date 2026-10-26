package cn.edu.whu.zhizhida.interview.api;

import java.util.List;

/** Material facts supplied by the application, independent of resume storage types. */
public record InterviewPlanningContext(String job, List<Project> projects, List<String> internships,
                                       List<String> skills, String summary) {
    public record Project(String name, String role, String contributions, String technologies) { }
}
