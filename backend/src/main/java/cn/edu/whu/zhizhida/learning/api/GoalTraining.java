package cn.edu.whu.zhizhida.learning.api;

import java.util.List;

/** 由学习模块定义的协作接口；学习模块不直接写入面试会话或训练任务的内部数据。 */
public interface GoalTraining {
    record Question(String id, int version, String title, String competencyId) { }
    record Item(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]{1,64}") String itemKey,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=64) String questionId,
            @jakarta.validation.constraints.Min(1) int questionVersion,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=160) String title,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=1000) String acceptance) { }
    record Result(String taskId, String sessionId, String questionId, boolean completed, boolean supported,
            String limitation, String sourceVersion, List<String> observedDimensions) { }
    /** 返回当前已发布题目；指定版本与当前版本不符时拒绝，账号须由已授权的调用方提供。 */
    Question question(long owner, String id, Integer version);
    /** 在计划确认事务中创建任务并返回编号；确认权限、计划幂等与整体回滚由调用方负责。 */
    String apply(long owner, String goalId, String planId, int revision, Item item);
    List<Result> results(long owner, String goalId);
    /** 在调用方事务内重新锁定来源；所需维度须由同一能力、轮次和评价标准支持，不能拼接不同回答。 */
    List<Result> results(long owner, String goalId, List<String> dimensions);
}
