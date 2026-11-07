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
    private KnowledgeText() {
    }

    public static List<Chunk> split(String id, int version, String text) {
        // 保留旧版 v1 的引用标识；后续版本各自使用不可变的引用标识。
        return split(version == 1 ? id : id + ":v" + version, text);
    }

    public static List<Chunk> split(String id, String text) {
        List<Chunk> result = new ArrayList<>();
        String heading = "";
        int start = 0, line = 1, startLine = 1;
        var matcher = Pattern.compile("[^\\n]*(?:\\n|$)").matcher(text);
        while (matcher.find() && matcher.start() < text.length()) {
            String value = matcher.group();
            boolean title = value.matches("(?s)#{1,6}\\s+.*");
            if (title || value.isBlank() || matcher.end() - start > 1000) {
                add(result, id, heading, text, start, matcher.start(), startLine, line - 1);
                start = matcher.start();
                startLine = line;
            }
            if (title)
                heading = value.replaceFirst("^#{1,6}\\s+", "").strip().substring(0,
                        Math.min(500, value.replaceFirst("^#{1,6}\\s+", "").strip().length()));
            if (value.isBlank()) {
                start = matcher.end();
                startLine = line + 1;
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

    public static int score(String text, String query) {
        return score(text, groups(query));
    }

    /** 标题只提供上下文，不单独作为可引用的事实证据。保留原始分块与偏移供历史引用使用。 */
    public static boolean isEvidence(Chunk chunk) {
        return chunk.content().lines().map(String::strip)
                .anyMatch(line -> !line.isBlank() && !line.matches("#{1,6}\\s+.*"));
    }

    /** 仅用于派生向量，引用仍取 chunk.content() 的原文。 */
    public static String embeddingText(Chunk chunk) {
        return chunk.heading() == null || chunk.heading().isBlank()
                ? chunk.content() : chunk.heading() + "\n" + chunk.content();
    }

    private static int score(String text, List<List<String>> groups) {
        String lower = lower(text);
        return groups.stream().filter(g -> g.stream().anyMatch(t -> contains(lower, t)))
                .mapToInt(g -> g.size() > 1 ? 10 : GENERIC.contains(g.getFirst()) ? 1 : 4).sum();
    }

    /**
     * 实时检索与归档训练来源共用同一套排序规则，不改动原文及其偏移量。
     */
    public static List<Hit> rank(List<Hit> candidates, String query, int limit) {
        var groups = groups(query);
        var covered = new HashSet<Integer>();
        var pool = new ArrayList<Hit>();
        for (var h : candidates) {
            if (!isEvidence(h.chunk()))
                continue;
            int body = score(h.chunk().content(), groups);
            if (body == 0 && score(h.chunk().heading(), groups) == 0)
                continue;
            pool.add(new Hit(h.documentId(), h.title(), h.sourceName(), h.version(), h.chunk(),
                    body + score(h.chunk().heading(), groups) / 3 + score(h.title(), groups) / 3));
        }
        var result = new ArrayList<Hit>();
        while (!pool.isEmpty() && result.size() < limit) {
            var order = Comparator.<Hit>comparingInt(h -> h.score() + novel(h, groups, covered)).reversed()
                    .thenComparing(Hit::documentId).thenComparingInt(h -> h.chunk().ordinal());
            var best = pool.stream().min(order).orElseThrow();
            pool.remove(best);
            result.add(best);
            for (int i = 0; i < groups.size(); i++) {
                String body = lower(best.chunk().content());
                if (groups.get(i).stream().anyMatch(t -> contains(body, t)))
                    covered.add(i);
            }
        }
        return List.copyOf(result);
    }

    private static int novel(Hit h, List<List<String>> groups, Set<Integer> covered) {
        int gain = 0;
        String body = lower(h.chunk().content());
        for (int i = 0; i < groups.size(); i++)
            if (!covered.contains(i) && groups.get(i).stream().anyMatch(t -> contains(body, t)))
                gain += groups.get(i).size() > 1 ? 8 : GENERIC.contains(groups.get(i).getFirst()) ? 0 : 4;
        return gain;
    }
}
