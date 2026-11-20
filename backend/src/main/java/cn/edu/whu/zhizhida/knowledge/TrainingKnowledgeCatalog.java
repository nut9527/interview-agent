package cn.edu.whu.zhizhida.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.interview.api.InterviewResources;
import cn.edu.whu.zhizhida.interview.api.KnowledgeCatalog;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;

/**
 * 为面试冻结资料快照，并从固定版本原文中检索训练引用。
 *
 * <p>
 * 快照保存完整正文、版本和内容哈希；后续检索沿用该快照，资料使用资格由调用方另行复核。
 */
@Service
public class TrainingKnowledgeCatalog implements KnowledgeCatalog {
    private final KnowledgeService documents;
    private final HybridKnowledgeSearch hybrid;

    @Override
    public String retrievalProfile() {
        return hybrid == null ? "bm25-lexical-blocks-v3" : hybrid.fingerprint();
    }

    public TrainingKnowledgeCatalog(KnowledgeService documents) {
        this(documents, (HybridKnowledgeSearch) null);
    }

    private TrainingKnowledgeCatalog(KnowledgeService documents, HybridKnowledgeSearch hybrid) {
        this.documents = documents;
        this.hybrid = hybrid;
    }

    @Autowired
    public TrainingKnowledgeCatalog(
            KnowledgeService documents, ObjectProvider<HybridKnowledgeSearch> hybrid) {
        this(documents, hybrid.getIfAvailable());
    }

    @Override
    public List<InterviewResources.Passage> recommend(String query) {
        return documents.search(query, List.of(), 3).stream()
                .map(
                        h -> new InterviewResources.Passage(
                                h.documentId(),
                                h.title(),
                                h.version(),
                                h.chunk().id(),
                                h.chunk().heading(),
                                h.chunk().startLine(),
                                h.chunk().endLine(),
                                h.chunk().content()))
                .toList();
    }

    /**
     * 对本场快照的完整分块统一排序，返回跨文档的前六个相关片段。
     *
     * <p>
     * 保留全局排名供调用方应用正文预算，随后再按资料分组，避免文档顺序影响引用取舍。
     */
    @Override
    public List<RankedExcerpt> rank(List<KnowledgeSnapshot> snapshots, String question) {
        if (snapshots == null || snapshots.isEmpty())
            return List.of();
        var candidates = new ArrayList<Hit>();
        var sources = new HashMap<String, KnowledgeSnapshot>();
        for (var source : snapshots) {
            sources.put(source.id(), source);
            for (var chunk : chunks(source)) {
                candidates.add(
                        new Hit(
                                source.id(),
                                source.title(),
                                source.sourceName(),
                                source.version(),
                                chunk,
                                0));
            }
        }
        var hits = hybrid == null
                ? KnowledgeText.rank(candidates, question, 6)
                : hybrid.search(snapshots, candidates, question, 6);
        return hits.stream()
                .map(hit -> new RankedExcerpt(sources.get(hit.documentId()), excerpt(hit.chunk())))
                .toList();
    }

    private static KnowledgeSnapshot.Excerpt excerpt(
            cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Chunk c) {
        // 保留整个有长度限制的片段，避免丢失尾部的有效信息。
        return new KnowledgeSnapshot.Excerpt(
                c.id(), c.heading(), c.startLine(), c.endLine(), c.content());
    }

    private static List<cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Chunk> chunks(KnowledgeSnapshot source) {
        boolean legacy = !source.excerpts().isEmpty()
                && source.excerpts().stream().noneMatch(e -> e.chunkId().contains(":b2:"));
        return legacy ? KnowledgeText.splitLegacy(source.id(), source.version(), source.content())
                : KnowledgeText.split(source.id(), source.version(), source.content());
    }

    /**
     * 将工具回执与本场快照逐字段核对，返回可进入引用目录的完整原文片段。
     *
     * <p>
     * 先校验正文哈希和版本身份，再核对片段编号、标题、行范围与正文；被裁剪的回执不作为完整引文。
     */
    @Override
    public List<KnowledgeSnapshot.Reference> verifiedExcerpts(
            List<KnowledgeSnapshot> snapshots, List<KnowledgeSnapshot.Reference> receipts) {
        if (receipts.isEmpty())
            return List.of();
        var sources = new HashMap<String, KnowledgeSnapshot>();
        var chunks = new HashMap<String, Set<KnowledgeSnapshot.Excerpt>>();
        for (var source : snapshots) {
            if (source.content() == null || !Sha256.hex(source.content()).equals(source.sha256()))
                continue;
            sources.put(source.id(), source);
        }
        var verified = new ArrayList<RankedExcerpt>();
        var seen = new HashSet<KnowledgeSnapshot.Excerpt>();
        for (var receipt : receipts) {
            var source = sources.get(receipt.id());
            if (source == null
                    || receipt.version() != source.version()
                    || !source.sha256().equals(receipt.sha256())
                    || !source.title().equals(receipt.title()))
                continue;
            var originals = chunks.computeIfAbsent(
                    source.id(),
                    ignored -> chunks(source)
                            .stream()
                            .map(TrainingKnowledgeCatalog::excerpt)
                            .collect(java.util.stream.Collectors.toSet()));
            for (var excerpt : receipt.excerpts()) {
                if (originals.contains(excerpt) && seen.add(excerpt))
                    verified.add(new RankedExcerpt(source, excerpt));
            }
        }
        return KnowledgeCatalog.references(verified);
    }

    /**
     * 在只读事务中读取当前账号选定的资料，固定本场使用的正文版本与初始摘录。
     *
     * <p>
     * 资料数量与总正文长度在冻结前统一校验；主题只用于选择摘录，快照仍保存完整正文供后续检索。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeSnapshot> snapshot(List<String> ids, String topic) {
        if (ids.isEmpty() || ids.size() > 5 || new HashSet<>(ids).size() != ids.size())
            throw new UserInputException("请选择 1～5 份不同资料。");
        var docs = ids.stream().map(documents::find).toList();
        if (docs.stream().mapToInt(d -> d.content().length()).sum() > 2000000)
            throw new UserInputException("本场资料总长度不能超过 2000000 字，请减少所选资料。");
        return docs.stream()
                .map(
                        d -> {
                            // 每份资料最多预载两个片段；正文预算由面试上下文组装环节统一控制。
                            var hits = documents.searchVersions(topic, List.of(d), 2);
                            var chunks = hits.isEmpty()
                                    ? d.chunks().stream().filter(KnowledgeText::isEvidence).limit(2).toList()
                                    : hits.stream().map(h -> h.chunk()).toList();
                            var excerpts = chunks.stream().map(TrainingKnowledgeCatalog::excerpt).toList();
                            return new KnowledgeSnapshot(
                                    d.id(),
                                    d.title(),
                                    d.sourceName(),
                                    d.version(),
                                    Sha256.hex(d.content()),
                                    d.content(),
                                    excerpts);
                        })
                .toList();
    }
}
