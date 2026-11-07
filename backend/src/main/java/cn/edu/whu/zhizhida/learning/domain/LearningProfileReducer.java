package cn.edu.whu.zhizhida.learning.domain;

import cn.edu.whu.zhizhida.learning.api.LearningMemory.*;
import java.util.*;

/**
 * 按能力、评价维度、量表版本和执行模式汇总活跃的定性学习观察。
 *
 * <p>输出观察总数、已观察、未观察和争议数量及最近记录；来源资格由调用方校验。
 */
public final class LearningProfileReducer {
    public static final String VERSION = "qualitative-observations-v1";
    public Profile reduce(long revision, List<Observation> observations) {
        var groups = new TreeMap<String, List<Observation>>();
        for (var row : observations) {
            if (!row.status().equals("ACTIVE") || row.fact() == null) continue;
            // 量表版本和执行模式共同参与分组，避免不同评价口径的观察混入同一统计。
            String key = String.join("/", row.competencyId(), row.dimension(), row.rubricVersion(), row.executionMode());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        var result = groups.values().stream().map(rows -> {
            rows.sort(Comparator.comparing(Observation::createdAt).thenComparing(Observation::id).reversed());
            var first = rows.getFirst();
            // 争议按主张状态独立计数，与观察状态并非互斥分类。
            return new Group(first.competencyId(), first.dimension(), first.rubricVersion(), first.executionMode(),
                    rows.size(), (int) rows.stream().filter(r -> r.fact().observation().name().equals("OBSERVED")).count(),
                    (int) rows.stream().filter(r -> r.fact().observation().name().equals("NOT_OBSERVED")).count(),
                    (int) rows.stream().filter(r -> r.fact().claim().status().name().equals("DISPUTED")).count(), first.id());
        }).toList();
        return new Profile(VERSION, revision, result);
    }
}
