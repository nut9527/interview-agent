package cn.edu.whu.zhizhida.ai.api;

import java.util.List;

/** 仅为派生索引；调用方先校验资料归属并限定范围，命中后仍须匹配已授权快照的版本、哈希与原文片段。 */
public interface KnowledgeVectors {
    String fingerprint();
    record Scope(String documentId, int version, String contentSha256) { }
    record Fragment(String chunkId, String text) { }
    record Match(Scope scope, String chunkId) { }
    /** 为固定版本的现有片段建立索引；调用方负责串行化同一索引代际的写入与删除。 */
    void index(Scope scope, List<Fragment> fragments);
    /** 精确删除一个派生代际，包括未完成写入，不调用 embedding。 */
    void remove(Scope scope, String indexFingerprint);
    List<Match> search(List<Scope> scopes, String query, int limit);
}
