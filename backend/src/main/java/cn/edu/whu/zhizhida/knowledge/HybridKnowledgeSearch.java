package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.ai.api.KnowledgeVectors;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.ErrorCode;
import cn.edu.whu.zhizhida.interview.api.KnowledgeSnapshot;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;

/**
 * 对已授权的不可变资料快照执行词项与向量检索的 RRF 排序融合。
 *
 * <p>向量结果提供排名，返回的正文与偏移取自业务归档；调用方负责确定可检索的资料范围。
 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agent.vector.enabled", havingValue = "true")
public final class HybridKnowledgeSearch {
    private final KnowledgeVectors vectors;
    private final MeterRegistry metrics;
    private final KnowledgeRetrievalSettings settings;
    public String fingerprint() { return settings.fingerprint() + ":" + vectors.retrievalFingerprint(); }
    public HybridKnowledgeSearch(KnowledgeVectors vectors, MeterRegistry metrics) {
        this(vectors, metrics, new KnowledgeRetrievalSettings());
    }
    @Autowired
    public HybridKnowledgeSearch(KnowledgeVectors vectors, MeterRegistry metrics, KnowledgeRetrievalSettings settings) {
        this.vectors = vectors; this.metrics = metrics; this.settings = settings;
    }
    /**
     * 在固定版本内融合两路排名，按章节分配名额后返回限定数量的片段。
     *
     * <p>候选集包含所选快照的完整分块，供向量命中核对原文。
     */
    public List<Hit> search(List<KnowledgeSnapshot> snapshots, List<Hit> candidates, String query, int limit) {
        if (limit <= 0 || snapshots.isEmpty()) return List.of();
        return diversify(rank(snapshots, candidates, query), limit);
    }
    List<Hit> rank(List<KnowledgeSnapshot> snapshots, List<Hit> candidates, String query) {
        var scopes = snapshots.stream().map(d -> new KnowledgeVectors.Scope(d.id(), d.version(), d.sha256())).toList();
        var allowed = snapshots.stream().map(d -> d.id() + ":" + d.version()).collect(java.util.stream.Collectors.toSet());
        // 两条召回链路共用证据口径；旧代际残留的纯标题向量也不能成为引用。
        var evidence = candidates.stream().filter(h -> allowed.contains(h.documentId() + ":" + h.version()))
                .filter(h -> KnowledgeText.isEvidence(h.chunk())).toList();
        var lexical = KnowledgeText.rank(evidence, query, settings.lexicalTopN());
        metrics.summary("knowledge.retrieval.candidates", "path", "lexical").record(lexical.size());
        List<KnowledgeVectors.Match> semantic;
        try {
            semantic = vectors.search(scopes, query, settings.vectorTopN());
        } catch (RuntimeException ex) {
            metrics.counter("knowledge.retrieval.failures", "reason", "vector_unavailable").increment();
            var failure = new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "资料检索服务暂不可用，请稍后重试。");
            failure.initCause(ex);
            throw failure;
        }
        var qualified = new HashMap<String, Hit>();
        for (var hit : evidence) qualified.put(key(hit), hit);
        var scores = new HashMap<String, Double>();
        // 两路分数尺度不同，按各自排名累计倒数权重，并以稳定片段键打破最终平分。
        for (int i = 0; i < lexical.size(); i++) scores.merge(key(lexical.get(i)), 1.0 / (settings.rrfK() + 1 + i), Double::sum);
        var seen = new HashSet<String>(); int rank = 0;
        for (var match : semantic) {
            // 向量命中只参与排序，必须重新匹配本场已授权的版本、哈希和片段。
            String key = match.scope().documentId() + ":" + match.scope().version() + ":" + match.chunkId();
            if (!scopes.contains(match.scope()) || !qualified.containsKey(key) || !seen.add(key)) continue;
            scores.merge(key, 1.0 / (settings.rrfK() + 1 + rank++), Double::sum);
        }
        metrics.summary("knowledge.retrieval.candidates", "path", "vector").record(rank);
        return scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .map(e -> qualified.get(e.getKey())).toList();
    }
    /**
     * 优先保留不同章节的高排名片段，再用延后候选补足结果数量。
     *
     * <p>章节名额是第一轮选择规则；无标题片段按自身编号分组，避免被合并为同一章节。
     */
    private List<Hit> diversify(List<Hit> ordered, int limit) {
        var result = new ArrayList<Hit>();
        var deferred = new ArrayList<Hit>();
        var counts = new HashMap<Section, Integer>();
        for (var hit : ordered) {
            String heading = hit.chunk().heading();
            var section = new Section(hit.documentId(), hit.version(), heading == null || heading.isBlank() ? hit.chunk().id() : heading.strip());
            if (counts.getOrDefault(section, 0) < settings.maxPerHeading()) {
                result.add(hit);
                counts.merge(section, 1, Integer::sum);
                if (result.size() == limit) return List.copyOf(result);
            } else deferred.add(hit);
        }
        // 小语料只有一个章节时仍可补齐证据，不因去重强行丢弃其余相关片段。
        for (var hit : deferred) {
            if (result.size() == limit) break;
            result.add(hit);
        }
        return List.copyOf(result);
    }
    private record Section(String documentId, int version, String heading) { }
    private static String key(Hit hit) { return hit.documentId() + ":" + hit.version() + ":" + hit.chunk().id(); }
}
