package cn.edu.whu.zhizhida.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Create;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Edit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Rename;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Restore;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Summary;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeImport;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;

/** 管理当前用户的资料与版本；正文更新生成新的分块归档，并以预期版本号防止并发覆盖。 */
@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final CurrentUserProvider users;
    private final HybridKnowledgeSearch hybrid;

    public KnowledgeService(KnowledgeRepository repository, CurrentUserProvider users) {
        this(repository, users, (HybridKnowledgeSearch) null);
    }

    private KnowledgeService(KnowledgeRepository repository, CurrentUserProvider users, HybridKnowledgeSearch hybrid) {
        this.repository = repository;
        this.users = users;
        this.hybrid = hybrid;
    }

    @Autowired
    public KnowledgeService(KnowledgeRepository repository, CurrentUserProvider users,
            ObjectProvider<HybridKnowledgeSearch> hybrid) {
        this(repository, users, hybrid.getIfAvailable());
    }

    public PageResult<Summary> page(PageRequest p) {
        return repository.page(users.requireUserId(), p);
    }

    public KnowledgeDocument find(String id) {
        return repository.find(users.requireUserId(), id).orElseThrow(() -> new ResourceNotFoundException("资料不存在"));
    }

    public KnowledgeDocument version(String id, int version) {
        return repository.version(users.requireUserId(), id, version)
                .orElseThrow(() -> new ResourceNotFoundException("资料版本不存在"));
    }

    public PageResult<Summary> versions(String id, PageRequest page) {
        Long owner = users.requireUserId();
        if (!repository.exists(owner, id))
            throw new ResourceNotFoundException("资料不存在");
        return repository.versions(owner, id, page);
    }

    @Transactional
    public KnowledgeDocument edit(String id, Edit request) {
        var current = find(id);
        String content = request.content();
        if (content == null || content.isBlank() || content.length() > 2000000 || content.indexOf('\0') >= 0
                || request.expectedVersion() == null || request.expectedVersion() < 1)
            throw new UserInputException("正文须为 1～2000000 字纯文本，并携带当前版本号。");
        int next = request.expectedVersion() + 1;
        var doc = new KnowledgeDocument(id, current.title(), current.sourceName(), next, content, null,
                KnowledgeText.split(id, next, content));
        if (!repository.edit(users.requireUserId(), doc, request.expectedVersion()))
            throw new BusinessException(ErrorCode.CONFLICT, "正文已在其他窗口更新，请保留本地编辑，核对最新版本后再保存。");
        return find(id);
    }

    // 恢复历史正文也创建新版本，避免改写已有引用依赖的历史版本。
    @Transactional
    public KnowledgeDocument restore(String id, int version, Restore request) {
        return edit(id, new Edit(version(id, version).content(), request.expectedVersion()));
    }

    @Transactional
    public KnowledgeDocument create(Create request) {
        return create(request.title(), request.content(), "粘贴笔记");
    }

    @Transactional
    public KnowledgeDocument confirmImport(KnowledgeImport.Confirm request) {
        if (!Boolean.TRUE.equals(request.confirmed()))
            throw new UserInputException("请先核对正文，再确认入库。");
        return create(request.title(), request.content(), KnowledgeDocumentImporter.sourceName(request.sourceName()));
    }

    private KnowledgeDocument create(String title, String content, String source) {
        Long user = lockOwner();
        if (repository.atCapacity(user))
            throw new UserInputException("最多保存 100 份资料，请先清理不需要的资料。");
        if (title == null || title.isBlank() || title.strip().length() > 160 || content == null || content.isBlank()
                || content.length() > 2000000 || content.indexOf('\0') >= 0)
            throw new UserInputException("标题须为 1～160 字，正文须为 1～2000000 字纯文本。");
        String id = UUID.randomUUID().toString();
        repository.insert(user,
                new KnowledgeDocument(id, title.strip(), source, 1, content, null, KnowledgeText.split(id, content)));
        return find(id);
    }

    /** 创建与确认导入共用账号锁；调用方必须已有事务，并先于导入任务行加锁。 */
    Long lockOwner() {
        Long user = users.requireUserId();
        repository.lockOwner(user);
        return user;
    }

    @Transactional
    public KnowledgeDocument rename(String id, Rename request) {
        find(id);
        if (!repository.rename(users.requireUserId(), id, request.title().strip()))
            throw new ResourceNotFoundException("资料不存在");
        return find(id);
    }

    @Transactional
    public void delete(String id) {
        if (!repository.delete(users.requireUserId(), id))
            throw new ResourceNotFoundException("资料不存在");
    }

    public List<Hit> search(String query, List<String> ids, int limit) {
        if (query == null || query.isBlank() || query.length() > 300)
            throw new UserInputException("请输入 1～300 字关键词或问题。");
        if (ids == null || ids.size() > 5 || ids.stream().anyMatch(Objects::isNull))
            throw new UserInputException("最多选择 5 份资料。");
        var selected = ids.stream().distinct().map(this::find).toList();
        // 未指定资料的全库推荐仍采用词项检索，避免一次读取整个账号的全部正文。
        if (hybrid != null && !selected.isEmpty())
            return hybridSearch(query, selected, limit);
        var tokens = KnowledgeText.tokens(query);
        return KnowledgeText.rank(repository.candidates(users.requireUserId(), ids, tokens), query, limit);
    }

    public List<Hit> searchVersions(String query, List<KnowledgeDocument> snapshots, int limit) {
        if (hybrid != null && !snapshots.isEmpty()) {
            if (snapshots.size() > 5)
                throw new UserInputException("最多选择 5 份资料。");
            // 不能信任调用方携带的正文或分块；按当前归属重读指定归档版本。
            var authorized = snapshots.stream().map(d -> version(d.id(), d.version())).toList();
            return hybridSearch(query, authorized, limit);
        }
        return KnowledgeText.rank(
                repository.versionCandidates(users.requireUserId(), snapshots, KnowledgeText.tokens(query)), query,
                limit);
    }

    private List<Hit> hybridSearch(String query, List<KnowledgeDocument> documents, int limit) {
        var snapshots = documents.stream()
                .map(d -> new KnowledgeSnapshot(d.id(), d.title(), d.sourceName(), d.version(),
                        Sha256.hex(d.content()), d.content(), List.of()))
                .toList();
        // 向量召回必须能命中没有任何 LIKE 词项重叠的正文；原文校验池取授权版本的所有片段。
        var candidates = documents.stream().flatMap(d -> d.chunks().stream()
                .map(c -> new Hit(d.id(), d.title(), d.sourceName(), d.version(), c, 0))).toList();
        return hybrid.search(snapshots, candidates, query, limit);
    }
}
