package cn.edu.whu.zhizhida.knowledge.domain;

import cn.edu.whu.zhizhida.common.UserInputException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Chunk;
import cn.edu.whu.zhizhida.knowledge.api.KnowledgeDocument.Hit;

/**
 * 偏移量以存储原文的 UTF-16 索引计数，与浏览器的字符串切片规则一致。
 */
public final class KnowledgeText {
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern IMAGE_LINE = Pattern.compile(
            "(?i)(?:(?:!\\[[^\\]]*]\\((?:[^()\\r\\n]|\\([^()\\r\\n]*\\))*\\)|<img\\b[^>]*>)\\s*)+");
    private KnowledgeText() {
    }

    public static List<Chunk> split(String id, int version, String text) {
        // 文档版本与分块规则分别编码，已有归档编号不变。
        return split(version == 1 ? id : id + ":v" + version, text);
    }

    public static List<Chunk> split(String id, String text) {
        var paragraphs = paragraphs(id, text, true);
        var result = new ArrayList<Chunk>();
        for (var chunk : paragraphs) {
            if (!result.isEmpty()) {
                var previous = result.getLast();
                if (isEvidence(previous) && isEvidence(chunk)
                        && previous.heading().equals(chunk.heading())
                        && !chunk.content().matches("(?s)^#{1,6}\\s+.*")
                        && chunk.endOffset() - previous.startOffset() <= 1000) {
                    result.set(result.size() - 1, new Chunk(previous.id(), previous.ordinal(), previous.heading(),
                            previous.startOffset(), chunk.endOffset(), previous.startLine(), chunk.endLine(),
                            text.substring(previous.startOffset(), chunk.endOffset())));
                    continue;
                }
            }
            int ordinal = result.size() + 1;
            result.add(new Chunk(id + ":b2:" + ordinal, ordinal, chunk.heading(), chunk.startOffset(),
                    chunk.endOffset(), chunk.startLine(), chunk.endLine(), chunk.content()));
        }
        return List.copyOf(result);
    }

    /** 已归档的面试摘录使用原分块编号和原边界。 */
    public static List<Chunk> splitLegacy(String id, int version, String text) {
        return paragraphs(version == 1 ? id : id + ":v" + version, text, false);
    }

    private static List<Chunk> paragraphs(String id, String text, boolean respectFences) {
        List<Chunk> result = new ArrayList<>();
        String heading = "";
        String fence = "";
        int start = 0, line = 1, startLine = 1;
        var matcher = Pattern.compile("[^\\n]*(?:\\n|$)").matcher(text);
        while (matcher.find() && matcher.start() < text.length()) {
            String value = matcher.group();
            boolean title = fence.isEmpty() && value.matches("(?s)#{1,6}\\s+.*");
            boolean blank = fence.isEmpty() && value.isBlank();
            if (title || blank || matcher.end() - start > 1000) {
                add(result, id, heading, text, start, matcher.start(), startLine, line - 1);
                start = matcher.start();
                startLine = line;
            }
            if (title)
                heading = value.replaceFirst("^#{1,6}\\s+", "").strip().substring(0,
                        Math.min(500, value.replaceFirst("^#{1,6}\\s+", "").strip().length()));
            if (blank) {
                start = matcher.end();
                startLine = line + 1;
            }
            if (respectFences) {
                var marker = FENCE.matcher(value.stripTrailing());
                if (marker.matches()) {
                    if (fence.isEmpty()) fence = marker.group(1);
                    else if (marker.group(1).charAt(0) == fence.charAt(0)
                            && marker.group(1).length() >= fence.length() && marker.group(2).isBlank()) fence = "";
                }
            }
            // 拆分超长行时保留原文位置，保证引用仍能准确定位。
            while (matcher.end() - start > 1000) {
                int end = start + 1000;
                if (Character.isHighSurrogate(text.charAt(end - 1)))
                    end--;
                add(result, id, heading, text, start, end, startLine, line);
                start = end;
                startLine = line;
            }
            if (value.endsWith("\n"))
                line++;
        }
        add(result, id, heading, text, start, text.length(), startLine, line);
        return List.copyOf(result);
    }

    private static void add(List<Chunk> rows, String id, String heading, String text, int start, int end, int first,
            int last) {
        if (end <= start || text.substring(start, end).isBlank())
            return;
        if (rows.size() >= 20000)
            throw new UserInputException("正文片段超过 20000 个，请合并短段落或拆分资料。");
        int ordinal = rows.size() + 1;
        rows.add(new Chunk(id + ":" + ordinal, ordinal, heading, start, end, first, Math.max(first, last),
                text.substring(start, end)));
    }

    // 每组别名只计分一次，避免扩充同义词后人为抬高来源的相关性。
    private static final List<List<String>> ALIASES = List.of(
            List.of("ttl", "过期时间", "生存时间", "有效期"),
            List.of("jitter", "随机抖动", "随机过期"),
            List.of("空值缓存", "缓存穿透", "空记录缓存"),
            List.of("single-flight", "singleflight", "请求合并", "同键加载"),
            List.of("read after write", "read-after-write", "写后读"),
            List.of("重复消费", "重复消息", "消息去重", "幂等处理"));
    private static final Set<String> GENERIC = Set.of("缓存", "商品", "数据", "处理", "记录", "实验", "方案", "问题", "时间", "请求");

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String text, String term) {
        if (!term.matches("[a-z0-9_-]+"))
            return text.contains(term);
        int at = text.indexOf(term);
        while (at >= 0) {
            int end = at + term.length();
            if ((at == 0 || !latin(text.charAt(at - 1))) && (end == text.length() || !latin(text.charAt(end))))
                return true;
            at = text.indexOf(term, at + 1);
        }
        return false;
    }

    private static boolean latin(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    private static List<List<String>> groups(String query) {
        String remaining = lower(query);
        var result = new ArrayList<List<String>>();
        for (var aliases : ALIASES)
            if (aliases.stream().anyMatch(a -> contains(lower(query), a))) {
                result.add(aliases);
                for (String alias : aliases)
                    remaining = remaining.replace(alias, " ");
            }
        for (String token : literalTokens(remaining))
            result.add(List.of(token));
        return result;
    }

    public static List<String> tokens(String query) {
        return groups(query).stream().flatMap(Collection::stream).distinct().toList();
    }

    private static List<String> literalTokens(String query) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+", Pattern.CASE_INSENSITIVE)
                .matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group().replaceAll("(是什么|为什么|如何|怎么|哪些|多少|请问|说明|多久|分别|具体|的|了|吗|和|及|与)", " ").trim();
            for (String part : word.split("\\s+")) {
                if (part.isEmpty())
                    continue;
                if (part.matches("[\\p{IsHan}]+") && part.length() > 2)
                    for (int i = 0; i < part.length() - 1; i++)
                        result.add(part.substring(i, i + 2));
                else if (!part.matches("[\\p{IsHan}]"))
                    result.add(part);
            }
        }
        return result.stream().limit(24).toList();
    }

    /** 标题只提供上下文，不单独作为可引用的事实证据。保留原始分块与偏移供历史引用使用。 */
    public static boolean isEvidence(Chunk chunk) {
        return chunk.content().lines().map(String::strip)
                .anyMatch(line -> !line.isBlank() && !line.matches("#{1,6}\\s+.*")
                        && !IMAGE_LINE.matcher(line).matches());
    }

    /** 仅用于派生向量，引用仍取 chunk.content() 的原文。 */
    public static String embeddingText(Chunk chunk) {
        return chunk.heading() == null || chunk.heading().isBlank()
                ? chunk.content() : chunk.heading() + "\n" + chunk.content();
    }

    /** BM25F：语料稀有度、词频饱和、正文长度归一化；标题只加权，不单独生成证据。 */
    public static List<Hit> rank(List<Hit> candidates, String query, int limit) {
        var groups = groups(query);
        var evidence = candidates.stream().filter(h -> isEvidence(h.chunk())).toList();
        if (evidence.isEmpty() || groups.isEmpty() || limit <= 0) return List.of();
        double averageLength = evidence.stream().mapToInt(h -> h.chunk().content().length()).average().orElseThrow();
        var frequencies = new ArrayList<double[]>();
        int[] documentFrequency = new int[groups.size()];
        for (var hit : evidence) {
            String body = lower(hit.chunk().content());
            String heading = lower(hit.chunk().heading());
            String title = lower(hit.title());
            double normalization = 0.25 + 0.75 * body.length() / averageLength;
            double[] terms = new double[groups.size()];
            for (int i = 0; i < groups.size(); i++) {
                var aliases = groups.get(i);
                int bodyCount = aliases.stream().mapToInt(t -> occurrences(body, t)).max().orElseThrow();
                int headingCount = aliases.stream().mapToInt(t -> occurrences(heading, t)).max().orElseThrow();
                // 文档标题不能让整个无关章节进入候选集。
                if (bodyCount == 0 && headingCount == 0) continue;
                int titleCount = aliases.stream().mapToInt(t -> occurrences(title, t)).max().orElseThrow();
                terms[i] = bodyCount / normalization + 0.35 * headingCount + 3 * titleCount;
                documentFrequency[i]++;
            }
            frequencies.add(terms);
        }
        double[] weights = new double[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            double idf = Math.log(1 + (evidence.size() - documentFrequency[i] + 0.5) / (documentFrequency[i] + 0.5));
            weights[i] = idf * (groups.get(i).size() > 1 ? 1.5 : GENERIC.contains(groups.get(i).getFirst()) ? 0.25 : 1);
        }
        var covered = new HashSet<Integer>();
        var pool = new ArrayList<Ranked>();
        for (int row = 0; row < evidence.size(); row++) {
            var h = evidence.get(row);
            double score = 0;
            double[] terms = frequencies.get(row);
            for (int i = 0; i < terms.length; i++) score += weights[i] * terms[i] * 2.2 / (terms[i] + 1.2);
            if (score > 0) pool.add(new Ranked(new Hit(h.documentId(), h.title(), h.sourceName(), h.version(), h.chunk(),
                    (int) Math.round(score * 1000)), score, terms));
        }
        var result = new ArrayList<Hit>();
        while (!pool.isEmpty() && result.size() < limit) {
            var order = Comparator.<Ranked>comparingDouble(h -> h.score() + novel(h.terms(), weights, covered)).reversed()
                    .thenComparing(h -> h.hit().documentId()).thenComparingInt(h -> h.hit().chunk().ordinal());
            var best = pool.stream().min(order).orElseThrow();
            pool.remove(best);
            result.add(best.hit());
            for (int i = 0; i < groups.size(); i++) if (best.terms()[i] > 0) covered.add(i);
        }
        return List.copyOf(result);
    }

    private record Ranked(Hit hit, double score, double[] terms) { }

    private static double novel(double[] terms, double[] weights, Set<Integer> covered) {
        double gain = 0;
        for (int i = 0; i < terms.length; i++) if (!covered.contains(i) && terms[i] > 0) gain += 0.35 * weights[i];
        return gain;
    }

    private static int occurrences(String text, String term) {
        boolean word = term.chars().allMatch(c -> latin((char) c) || c == '-');
        int count = 0;
        for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + term.length())) {
            int end = at + term.length();
            if (!word || (at == 0 || !latin(text.charAt(at - 1))) && (end == text.length() || !latin(text.charAt(end)))) count++;
        }
        return count;
    }
}
