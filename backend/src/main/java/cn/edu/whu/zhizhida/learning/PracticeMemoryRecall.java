package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.learning.api.LearningMemory.Observation;
import java.util.*;

/** 先覆盖不同能力和维度，再补充历史场次；单次训练的重复轮次不挤占跨场记忆。 */
final class PracticeMemoryRecall {
    private static final Comparator<Observation> RECENT = Comparator.comparing(Observation::createdAt)
            .thenComparing(Observation::sourceRound).thenComparing(Observation::id).reversed();

    static List<Observation> select(List<Observation> observations, int limit) {
        var competencies = new TreeMap<String, Map<String, List<Observation>>>();
        var seen = new HashSet<List<String>>();
        observations.stream().sorted(RECENT).forEach(row -> {
            var key = List.of(row.competencyId(), row.dimension(), row.rubricVersion(), row.sourceSessionId());
            if (seen.add(key)) competencies.computeIfAbsent(row.competencyId(), ignored -> new TreeMap<>())
                    .computeIfAbsent(row.dimension()+"/"+row.rubricVersion(), ignored -> new ArrayList<>()).add(row);
        });
        var queues = new ArrayList<List<Observation>>();
        for (var dimensions : competencies.values()) {
            var groups = new ArrayList<>(dimensions.values());
            groups.sort((left, right) -> RECENT.compare(left.getFirst(), right.getFirst()));
            queues.add(interleave(groups, Integer.MAX_VALUE));
        }
        return interleave(queues, limit);
    }

    private static List<Observation> interleave(List<? extends List<Observation>> groups, int limit) {
        var selected = new ArrayList<Observation>();
        for (int depth = 0; selected.size() < limit; depth++) {
            boolean added = false;
            for (var group : groups) {
                if (depth < group.size()) {
                    selected.add(group.get(depth));
                    added = true;
                    if (selected.size() == limit) return List.copyOf(selected);
                }
            }
            if (!added) break;
        }
        return List.copyOf(selected);
    }
}
