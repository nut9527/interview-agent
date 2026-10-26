package cn.edu.whu.zhizhida.health;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Service
public class ReadinessService {
    private final DataSource dataSource;

    public ReadinessService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isReady() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException ex) {
            return false;
        }
    }
}
