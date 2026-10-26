package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.AuthResponse;
import cn.edu.whu.zhizhida.auth.api.DemoAccounts;
import cn.edu.whu.zhizhida.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class DefaultDemoAccounts implements DemoAccounts {
    static final String DEMO_USERNAME = "demo";
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final DefaultAuthService auth;

    public DefaultDemoAccounts(UserAccountRepository users, PasswordEncoder passwords, DefaultAuthService auth) {
        this.users = users;
        this.passwords = passwords;
        this.auth = auth;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SandboxAccount createSandbox(int ttlHours, int maxActiveSandboxes) {
        users.lockDemoCapacity();
        if (users.countActiveDemoSandboxes() >= maxActiveSandboxes) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "演示空间已满，请稍后再试");
        }
        UserAccount template = users.findById(ensureTemplate()).orElseThrow();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(ttlHours));
        UserAccount sandbox = users.createDemoSandbox(
                "demo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                passwords.encode(UUID.randomUUID().toString()), template,
                LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()));
        return new SandboxAccount(sandbox.id(), template.id(), expiresAt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long ensureTemplate() {
        users.lockDemoCapacity();
        UserAccount template = users.findByUsername(DEMO_USERNAME).map(account -> {
            if (account.accountType() != AccountType.DEMO_TEMPLATE) {
                throw new BusinessException(HttpStatus.CONFLICT, "演示模板账号名称已被占用，请联系管理员");
            }
            return account;
        }).orElseGet(() -> users.createDemoTemplate(DEMO_USERNAME,
                passwords.encode(UUID.randomUUID().toString()), "演示用户", "java后端工程师",
                List.of("Java", "MySQL", "Spring Boot", "Redis", "Docker", "React", "Git")));
        return template.id();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthResponse openSession(SandboxAccount sandbox) {
        UserAccount account = users.findById(sandbox.userId()).orElseThrow();
        if (account.accountType() != AccountType.DEMO_SANDBOX || account.isExpiredDemoSandbox(LocalDateTime.now())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "演示空间已失效");
        }
        return auth.createSession(account, sandbox.expiresAt());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> lockExpiredIds(int batchSize) { return users.lockExpiredDemoSandboxIds(batchSize); }

    @Override
    public long countExpired() { return users.countExpiredDemoSandboxes(); }
}
