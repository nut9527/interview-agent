package cn.edu.whu.zhizhida.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new HealthController("mock", "0.1.0-SNAPSHOT"),
                new ReadinessController(new ReadinessService(dataSource))).build();
    }

    @Test
    void livenessReportsBuildVersionAndStorageWithoutQueryingDatabase() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storage").value("mysql"))
                .andExpect(jsonPath("$.data.version").value("0.1.0-SNAPSHOT"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void readyRequiresValidDatabaseConnectionAndReturnsItToPool() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        mvc.perform(get("/api/health/ready")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.database").value("up"));
        verify(connection).close();
    }

    @Test
    void invalidConnectionIsNotReadyAndIsClosed() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);
        mvc.perform(get("/api/health/ready")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
        verify(connection).close();
    }

    @Test
    void disconnectedDatabaseIs503WithoutLeakingDriverDetails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("jdbc password=do-not-expose"));
        mvc.perform(get("/api/health/ready")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.database").value("down"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-expose"))));
    }

    @Test
    void validationExceptionAlsoReturnsConnectionToPool() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenThrow(new SQLException("connection lost"));
        mvc.perform(get("/api/health/ready")).andExpect(status().isServiceUnavailable());
        verify(connection).close();
    }
}
