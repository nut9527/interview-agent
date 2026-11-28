package cn.edu.whu.zhizhida.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.edu.whu.zhizhida.learning.api.GoalSessionLinks;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class LearningGoalSessionLinksTest {
    @Test
    void attachmentRequiresTheCallersTransactionAndRollsBackWithIt() throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var manager = new DataSourceTransactionManager(source);
        var jdbc = mock(JdbcTemplate.class);
        var proxy = new ProxyFactory(new LearningGoalSessionLinks(jdbc));
        proxy.addAdvice(
                new TransactionInterceptor(
                        (TransactionManager) manager, new AnnotationTransactionAttributeSource()));
        var links = (GoalSessionLinks) proxy.getProxy();
        var link = new GoalSessionLinks.Link("goal", "session", "task", "question");

        assertThatThrownBy(() -> links.attach(42, link))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(jdbc, connection);

        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                status -> {
                                                    links.attach(42, link);
                                                    throw new IllegalStateException(
                                                            "run commit failed");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(jdbc)
                .update(
                        anyString(),
                        eq(42L),
                        eq("goal"),
                        eq("session"),
                        eq("task"),
                        eq("question"));
    }
}
