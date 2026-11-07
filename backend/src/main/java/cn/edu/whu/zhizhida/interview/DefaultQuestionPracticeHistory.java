package cn.edu.whu.zhizhida.interview;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.interview.api.QuestionPracticeHistory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class DefaultQuestionPracticeHistory implements QuestionPracticeHistory {
    private final CurrentUserProvider users;
    private final QuestionPracticeRepository repository;
    public DefaultQuestionPracticeHistory(CurrentUserProvider users, QuestionPracticeRepository repository) {
        this.users = users; this.repository = repository;
    }
    public List<Progress> progress() { return repository.progress(users.requireUserId()); }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<Practice> history(String id, PageRequest page) { return repository.history(users.requireUserId(), id, page); }
}
