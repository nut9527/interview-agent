package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.persistence.SessionEntity;
import cn.edu.whu.zhizhida.auth.persistence.SessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 以 令牌 摘要为键持久化会话，认证查询只返回仍在有效期内的用户编号。
 * 会话有效期与账号有效性分别校验，后者由认证服务负责。
 */
@Repository
public class SessionRepository {

    private final SessionMapper sessionMapper;

    public SessionRepository(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    public void create(String tokenHash, Long userId, Instant expiresAt) {
        deleteExpired();
        SessionEntity entity = new SessionEntity();
        entity.setTokenHash(tokenHash);
        entity.setUserId(userId);
        entity.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()));
        sessionMapper.insert(entity);
    }

    public Optional<Long> findActiveUserId(String tokenHash) {
        SessionEntity entity = sessionMapper.selectOne(
                Wrappers.<SessionEntity>lambdaQuery()
                        .eq(SessionEntity::getTokenHash, tokenHash)
                        .gt(SessionEntity::getExpiresAt, LocalDateTime.now())
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(entity).map(SessionEntity::getUserId);
    }

    public void delete(String tokenHash) {
        sessionMapper.deleteById(tokenHash);
    }

    public int deleteByUserId(Long userId) {
        return sessionMapper.delete(
                Wrappers.<SessionEntity>lambdaQuery()
                        .eq(SessionEntity::getUserId, userId)
        );
    }

    public int deleteExpired() {
        return sessionMapper.delete(
                Wrappers.<SessionEntity>lambdaQuery()
                        .le(SessionEntity::getExpiresAt, LocalDateTime.now())
        );
    }
}
