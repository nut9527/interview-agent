package cn.edu.whu.zhizhida.knowledge;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cn.edu.whu.zhizhida.common.SourceRevokedException;
import cn.edu.whu.zhizhida.interview.api.InterviewSourceAccess;

/** 现存资料记录是授权依据；更新保留资料身份，删除则撤销所有版本的访问权限。 */
@Service
public class KnowledgeInterviewSourceAccess implements InterviewSourceAccess {
    private final KnowledgeRepository documents;

    public KnowledgeInterviewSourceAccess(KnowledgeRepository documents) {
        this.documents = documents;
    }

    @Override
    public List<String> unavailable(long owner, List<String> ids) {
        var found = documents.ownedIds(owner, ids, false);
        return ids.stream().filter(id -> !found.contains(id)).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForUse(long owner, List<String> ids) {
        if (documents.ownedIds(owner, ids, true).size() != ids.size())
            throw new SourceRevokedException();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrentVersion(long owner, String id, int version, String sha256) {
        if (!documents.currentVersion(owner, id, version, sha256))
            throw new cn.edu.whu.zhizhida.common.BusinessException(cn.edu.whu.zhizhida.common.ErrorCode.CONFLICT,
                    "资料版本已变化，请核对当前正文后重新加入计划。");
    }
}
