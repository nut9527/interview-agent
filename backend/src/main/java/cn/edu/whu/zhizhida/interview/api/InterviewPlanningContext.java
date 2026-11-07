package cn.edu.whu.zhizhida.interview.api;

import java.util.List;

/** 由应用层提供的材料事实，不依赖简历的存储类型。 */
public record InterviewPlanningContext(String job, List<Project> projects, List<String> internships,
                                       List<String> skills, String summary) {
    public record Project(String name, String role, String contributions, String technologies) { }
}
