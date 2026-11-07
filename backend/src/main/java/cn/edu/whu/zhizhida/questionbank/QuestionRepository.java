package cn.edu.whu.zhizhida.questionbank;

import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.interview.api.InterviewDifficulty;
import cn.edu.whu.zhizhida.questionbank.api.Question;
import cn.edu.whu.zhizhida.questionbank.api.QuestionTopic;
import java.util.Optional;

public interface QuestionRepository {
    record State(int version, boolean published) { }
    Optional<State> state(String id);
    /** 调用方须持有事务；以共享锁保护发布状态与当前版本，防止提交期间被下架或修订。 */
    Optional<State> lockState(String id);
    PageResult<Question.Summary> page(PageRequest page, QuestionTopic topic, InterviewDifficulty difficulty, String keyword);
    Optional<Question> find(String id);
    /** 仅读取不可变归档，不代表题目仍可使用；调用方须另外检查当前发布状态和授权。 */
    Optional<cn.edu.whu.zhizhida.interview.api.QuestionSnapshot> snapshot(String id, int version);
    java.util.List<Question.Summary> candidates(QuestionTopic topic, InterviewDifficulty difficulty, String keyword);
}

