package cn.edu.whu.zhizhida.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 补充 Java 类型规则：检查源码中的 SQL 表名；不把它当作运行时数据库权限控制。 */
class SqlModuleBoundaryTest {
    private static final Pattern LEARNING_SQL = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+`?learning_[a-z0-9_]+\\b");

    @Test void learningTablesAreOnlyAccessedInsideTheirOwningModule() throws Exception {
        Path root = Path.of("src/main/java/cn/edu/whu/zhizhida");
        try (var files = Files.walk(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (root.relativize(file).startsWith("learning")) continue;
                // 合并 SQL 字符串的普通续行，覆盖 FROM 与表名分处两个字面量的写法。
                String source = Files.readString(file).replaceAll("\"\\s*\\+\\s*\"", "");
                assertThat(LEARNING_SQL.matcher(source).find())
                        .as("%s must access learning data through learning.api", file).isFalse();
            }
        }
    }

    @Test void guardCoversReadWriteAndJoinedSqlWithoutRejectingToolNames() {
        for (String sql : new String[] {"SELECT * FROM learning_goal_sessions", "insert into learning_goals values (?)",
                "UPDATE `learning_observations` SET status=?", "DELETE t FROM interview_runs t JOIN learning_goals g ON t.id=g.id"})
            assertThat(LEARNING_SQL.matcher(sql).find()).as(sql).isTrue();
        assertThat(LEARNING_SQL.matcher("get_learning_progress").find()).isFalse();
    }
}
