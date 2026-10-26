package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.BackgroundAccountAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DefaultBackgroundAccountAccess implements BackgroundAccountAccess {
    private final JdbcTemplate jdbc;
    public DefaultBackgroundAccountAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActive(Long owner) {
        return !jdbc.query("SELECT id FROM app_users WHERE id=? AND (account_type='STANDARD' OR "
                + "(account_type='DEMO_SANDBOX' AND demo_expires_at>NOW())) FOR UPDATE", (rs, row) -> rs.getLong(1), owner).isEmpty();
    }
}
