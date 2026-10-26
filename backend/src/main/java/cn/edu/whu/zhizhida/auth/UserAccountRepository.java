package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.persistence.UserAccountEntity;
import cn.edu.whu.zhizhida.auth.persistence.UserAccountMapper;
import cn.edu.whu.zhizhida.auth.api.UserProfileRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public class UserAccountRepository {

    private static final String TAG_SEPARATOR = "\u001f";
    private final UserAccountMapper userAccountMapper;
    private final JdbcTemplate jdbc;

    public UserAccountRepository(UserAccountMapper userAccountMapper, JdbcTemplate jdbc) {
        this.userAccountMapper = userAccountMapper;
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        UserAccountEntity entity = userAccountMapper.selectOne(
                Wrappers.<UserAccountEntity>lambdaQuery()
                        .eq(UserAccountEntity::getUsername, username)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(entity).map(this::mapAccount);
    }

    public Optional<UserAccount> findById(Long id) {
        return Optional.ofNullable(userAccountMapper.selectById(id)).map(this::mapAccount);
    }

    public void lockById(Long id) {
        if (jdbc.query("SELECT id FROM app_users WHERE id=? FOR UPDATE", (rs, row) -> rs.getLong(1), id).isEmpty()) {
            throw new cn.edu.whu.zhizhida.common.BusinessException(cn.edu.whu.zhizhida.common.ErrorCode.AUTH_REQUIRED, "登录状态已失效");
        }
    }

    public UserAccount create(String username, String passwordHash, String displayName) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordHash);
        entity.setDisplayName(displayName);
        entity.setTargetRole("");
        entity.setSkillTags("");
        entity.setAccountType(AccountType.STANDARD.name());
        userAccountMapper.insert(entity);
        return mapAccount(entity);
    }

    public UserAccount createDemoTemplate(String username, String passwordHash, String displayName,
                                          String targetRole, List<String> skillTags) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordHash);
        entity.setDisplayName(displayName);
        entity.setTargetRole(targetRole);
        entity.setSkillTags(String.join(TAG_SEPARATOR, skillTags));
        entity.setAccountType(AccountType.DEMO_TEMPLATE.name());
        userAccountMapper.insert(entity);
        return mapAccount(entity);
    }

    public UserAccount createDemoSandbox(String username, String passwordHash, UserAccount template,
                                         LocalDateTime expiresAt) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordHash);
        entity.setDisplayName(template.displayName());
        entity.setTargetRole(template.targetRole());
        entity.setSkillTags(String.join(TAG_SEPARATOR, template.skillTags()));
        entity.setAccountType(AccountType.DEMO_SANDBOX.name());
        entity.setDemoExpiresAt(expiresAt);
        userAccountMapper.insert(entity);
        return mapAccount(entity);
    }

    public UserAccount markAsDemoTemplate(Long userId) {
        userAccountMapper.update(
                null,
                Wrappers.<UserAccountEntity>lambdaUpdate()
                        .eq(UserAccountEntity::getId, userId)
                        .set(UserAccountEntity::getAccountType, AccountType.DEMO_TEMPLATE.name())
                        .set(UserAccountEntity::getDemoExpiresAt, null)
        );
        return findById(userId).orElseThrow();
    }

    public void lockDemoCapacity() {
        jdbc.queryForObject("SELECT id FROM demo_capacity_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public List<Long> lockExpiredDemoSandboxIds(int batchSize) {
        return jdbc.query("SELECT id FROM app_users WHERE account_type = 'DEMO_SANDBOX' "
                        + "AND demo_expires_at <= NOW() ORDER BY demo_expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, row) -> rs.getLong(1), batchSize);
    }

    public long countExpiredDemoSandboxes() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE account_type = 'DEMO_SANDBOX' "
                + "AND demo_expires_at <= NOW()", Long.class);
        return count == null ? 0 : count;
    }

    public long countActiveDemoSandboxes() {
        return userAccountMapper.selectCount(
                Wrappers.<UserAccountEntity>lambdaQuery()
                        .eq(UserAccountEntity::getAccountType, AccountType.DEMO_SANDBOX.name())
                        .apply("demo_expires_at > NOW()")
        );
    }

    public UserAccount updateProfile(Long userId, UserProfileRequest request) {
        List<String> skillTags = request.skillTags() == null ? List.of() : request.skillTags();
        userAccountMapper.update(
                null,
                Wrappers.<UserAccountEntity>lambdaUpdate()
                        .eq(UserAccountEntity::getId, userId)
                        .set(UserAccountEntity::getDisplayName, request.displayName())
                        .set(UserAccountEntity::getTargetRole, request.targetRole())
                        .set(UserAccountEntity::getSkillTags, String.join(TAG_SEPARATOR, skillTags))
        );
        return findById(userId).orElseThrow();
    }

    public void updatePassword(Long userId, String passwordHash) {
        int updated = userAccountMapper.update(
                null,
                Wrappers.<UserAccountEntity>lambdaUpdate()
                        .eq(UserAccountEntity::getId, userId)
                        .set(UserAccountEntity::getPasswordHash, passwordHash)
        );
        if (updated == 0) {
            throw new IllegalStateException("user account not found: " + userId);
        }
    }

    public boolean deleteById(Long userId) {
        userAccountMapper.deleteLegacyReports(userId);
        return userAccountMapper.deleteById(userId) > 0;
    }

    private UserAccount mapAccount(UserAccountEntity entity) {
        List<String> tags = entity.getSkillTags() == null || entity.getSkillTags().isBlank()
                ? List.of()
                : Arrays.stream(entity.getSkillTags().split(TAG_SEPARATOR)).filter(tag -> !tag.isBlank()).toList();
        AccountType accountType = entity.getAccountType() == null
                ? AccountType.STANDARD
                : AccountType.valueOf(entity.getAccountType());
        return new UserAccount(entity.getId(), entity.getUsername(), entity.getPasswordHash(),
                entity.getDisplayName(), entity.getTargetRole(), tags, accountType, entity.getDemoExpiresAt());
    }
}
