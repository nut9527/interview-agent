package cn.edu.whu.zhizhida.knowledge;

import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeLearning.Generate;
import cn.edu.whu.zhizhida.knowledge.domain.KnowledgeText;
import java.util.*;

/** 在固定版本内选材：指定片段保持原范围，自动选材覆盖全文或围绕用户重点检索。 */
final class QuizSourceSelector {
    @FunctionalInterface
    interface FocusSearch {
        List<Hit> search(KnowledgeDocument document, String query, int limit);
    }

    static List<Hit> select(List<KnowledgeDocument> documents,Generate request) {
        return select(documents, request, (doc, query, limit) -> KnowledgeText.rank(
                doc.chunks().stream().map(c -> new Hit(doc.id(), doc.title(), doc.sourceName(), doc.version(), c, 0))
                        .filter(QuizSourceSelector::substantive).toList(), query, limit));
    }

    static List<Hit> select(List<KnowledgeDocument> documents, Generate request, FocusSearch search) {
        int explicit=request.sources().stream().mapToInt(s->new HashSet<>(s.chunkIds()).size()).sum();
        long automatic=request.sources().stream().filter(s->s.chunkIds().isEmpty()).count();
        if(explicit+automatic>18) throw new UserInputException("一次最多选取18个片段，请减少指定范围。");
        var result=new ArrayList<Hit>(); int remaining=18-explicit;
        for(int i=0;i<documents.size();i++) {
            var doc=documents.get(i); var ids=request.sources().get(i).chunkIds();
            var all=doc.chunks().stream().map(c->new Hit(doc.id(),doc.title(),doc.sourceName(),doc.version(),c,0)).toList();
            if(!ids.isEmpty()) {
                for(String id:new LinkedHashSet<>(ids)) {
                    var selected=all.stream().filter(h->h.chunk().id().equals(id)).findFirst()
                            .orElseThrow(()->new UserInputException("所选片段不属于该资料版本"));
                    if(!KnowledgeText.isEvidence(selected.chunk())) throw new UserInputException("所选片段只有标题，请选择包含正文的片段。");
                    result.add(selected);
                }
                continue;
            }
            int limit=remaining/(int)automatic--; remaining-=limit;
            var pool=all.stream().filter(QuizSourceSelector::substantive).toList();
            if(!request.focus().isBlank()) {
                // 检索器可使用混合召回；只接纳当前归档的实质片段，仍由本层控制出题范围。
                var allowed=pool.stream().map(h -> h.chunk().id()).collect(java.util.stream.Collectors.toSet());
                var ranked=search.search(doc,request.focus(),limit).stream()
                        .filter(h -> h.documentId().equals(doc.id()) && h.version()==doc.version()
                                && allowed.contains(h.chunk().id())).limit(limit).toList();
                var selected=new LinkedHashMap<String,Hit>();
                ranked.stream().limit(Math.max(1,limit/2)).forEach(h->selected.put(h.chunk().id(),h));
                // 相关段落邻接上下文优先补齐，不用无关章节填满名额。
                for(var hit:ranked) {
                    if(selected.size()>=limit) break;
                    pool.stream().filter(h->h.chunk().ordinal()==hit.chunk().ordinal()+1
                            && h.chunk().heading().equals(hit.chunk().heading()))
                            .findFirst().ifPresent(h->selected.putIfAbsent(h.chunk().id(),h));
                }
                for(var hit:ranked) { if(selected.size()>=limit) break; selected.putIfAbsent(hit.chunk().id(),hit); }
                result.addAll(selected.values());
            } else {
                // 分区采样避免只读书籍开头；每区选择信息更完整的段落。
                for(int bucket=0;bucket<Math.min(limit,pool.size());bucket++) {
                    int size=Math.min(limit,pool.size());
                    int start=bucket*pool.size()/size,end=(bucket+1)*pool.size()/size;
                    pool.subList(start,end).stream().max(Comparator.comparingInt(h->h.chunk().content().length())).ifPresent(result::add);
                }
            }
        }
        return List.copyOf(result);
    }
    private static boolean substantive(Hit hit) {
        String body=hit.chunk().content().strip();
        if(!KnowledgeText.isEvidence(hit.chunk())) return false;
        String heading=hit.chunk().heading().replaceAll("^[\\d.、\\s]+", "");
        if(heading.matches("(?i).{0,12}(前言|目录|作者介绍|关于作者|版权声明|致谢|preface|contents)")) return false;
        // 目录页通常由短标题、点线与页码构成，不应当作技术依据。
        long entries=body.lines().filter(line->line.matches(".*(?:\\.{3,}|…{2,}|·{3,})\\s*\\d+\\s*")).count();
        return entries<2;
    }
}
