package cn.edu.whu.zhizhida.common.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RunExecutorRegistryTest {
    @Test
    void resolvesExecutorsOnlyAfterTheirPortDependenciesAreConstructed() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RunExecutorRegistry.class);
            context.registerBean(SubmissionPort.class);
            context.registerBean(GoalExecutor.class);
            context.refresh();

            var registry = context.getBean(RunExecutorRegistry.class);
            assertThat(registry.require("GOAL")).isSameAs(context.getBean(GoalExecutor.class));
            assertThat(context.getBean(GoalExecutor.class).port.registry).isSameAs(registry);
            assertThatThrownBy(() -> registry.require("UNKNOWN"))
                    .isInstanceOfSatisfying(RunFailureException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RUN_SUBJECT_UNSUPPORTED"));
            assertThatThrownBy(() -> registry.require(null)).hasMessage("RUN_SUBJECT_UNSUPPORTED");
        }
    }

    @Test
    void duplicateSubjectTypeFailsStartupRatherThanSelectingOneExecutor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RunExecutorRegistry.class);
            context.registerBean("first", RunExecutor.class, () -> executor("GOAL"));
            context.registerBean("second", RunExecutor.class, () -> executor("GOAL"));

            assertThatThrownBy(context::refresh).hasMessage("RUN_EXECUTOR_DUPLICATE: GOAL");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingSubjectTypeFailsStartup(String type) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RunExecutorRegistry.class);
            context.registerBean(RunExecutor.class, () -> executor(type));
            assertThatThrownBy(context::refresh).hasMessage("RUN_SUBJECT_TYPE_INVALID");
        }
    }

    private static RunExecutor executor(String type) {
        return new RunExecutor() {
            public String subjectType() { return type; }
            public void lock(long owner, String subjectId) { }
            public void validate(long owner, String subjectId, long revision, String operation) { }
            public void execute(ExecutionPort.Context context, String subjectId, String operation, String input) { }
        };
    }

    static final class SubmissionPort {
        final RunExecutorRegistry registry;
        SubmissionPort(RunExecutorRegistry registry) { this.registry = registry; }
    }

    static final class GoalExecutor implements RunExecutor {
        final SubmissionPort port;
        GoalExecutor(SubmissionPort port) { this.port = port; }
        public String subjectType() { return "GOAL"; }
        public void lock(long owner, String subjectId) { }
        public void validate(long owner, String subjectId, long revision, String operation) { }
        public void execute(ExecutionPort.Context context, String subjectId, String operation, String input) { }
    }
}
