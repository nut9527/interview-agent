package cn.edu.whu.zhizhida.knowledge;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 人工标注以资料/章节为单位；重复片段占据排名但不能重复获得相关性收益。 */
final class KnowledgeRetrievalMetrics {
    private KnowledgeRetrievalMetrics() {}

    record Sample(String kind, List<String> expected, List<String> retrieved) {}
    record Ranking(double recall, double reciprocalRank, double ndcg) {}

    static Ranking at(List<String> expected, List<String> retrieved, int k) {
        if (k < 1 || expected.isEmpty()) throw new IllegalArgumentException("Positive labels and k required");
        var relevant = new HashSet<>(expected);
        var seen = new HashSet<String>();
        int recalled = 0;
        double reciprocalRank = 0, dcg = 0, ideal = 0;
        for (int i = 0; i < Math.min(k, retrieved.size()); i++) {
            String source = retrieved.get(i);
            if (relevant.contains(source) && seen.add(source)) {
                recalled++;
                if (reciprocalRank == 0) reciprocalRank = 1.0 / (i + 1);
                dcg += 1.0 / log2(i + 2);
            }
        }
        for (int i = 0; i < Math.min(k, relevant.size()); i++) ideal += 1.0 / log2(i + 2);
        return new Ranking((double) recalled / relevant.size(), reciprocalRank, dcg / ideal);
    }

    private static double log2(int n) { return Math.log(n) / Math.log(2); }

    static Map<String, Object> summarize(List<Sample> samples, List<Integer> cutoffs) {
        var summary = new LinkedHashMap<String, Object>();
        var positives = samples.stream().filter(s -> !s.expected().isEmpty()).toList();
        long noAnswer = samples.size() - positives.size();
        long empty = samples.stream().filter(s -> s.expected().isEmpty() && s.retrieved().isEmpty()).count();
        summary.put("cases", samples.size());
        summary.put("positiveCases", positives.size());
        summary.put("noAnswerCases", noAnswer);
        summary.put("noAnswerWithEmptyRetrieval", empty);
        // 这是检索是否留空，不能冒充端到端拒答准确率。
        summary.put("emptyRetrievalRateOnNoAnswer", noAnswer == 0 ? null : (double) empty / noAnswer);
        var ranking = new LinkedHashMap<String, Object>();
        for (int k : cutoffs) {
            var scores = positives.stream().map(s -> at(s.expected(), s.retrieved(), k)).toList();
            var metrics = new LinkedHashMap<String, Object>();
            metrics.put("recall", scores.isEmpty() ? null : scores.stream().mapToDouble(Ranking::recall).average().orElseThrow());
            metrics.put("mrr", scores.isEmpty() ? null : scores.stream().mapToDouble(Ranking::reciprocalRank).average().orElseThrow());
            metrics.put("ndcg", scores.isEmpty() ? null : scores.stream().mapToDouble(Ranking::ndcg).average().orElseThrow());
            ranking.put(Integer.toString(k), metrics);
        }
        summary.put("atK", ranking);
        return summary;
    }

    static Map<String, Object> byKind(List<Sample> samples, List<Integer> cutoffs) {
        var grouped = samples.stream().collect(Collectors.groupingBy(Sample::kind, TreeMap::new, Collectors.toList()));
        var summary = new LinkedHashMap<String, Object>();
        summary.put("all", summarize(samples, cutoffs));
        grouped.forEach((kind, rows) -> summary.put(kind, summarize(rows, cutoffs)));
        return summary;
    }
}
