package cn.edu.whu.zhizhida.auth;

import java.util.List;
import java.time.LocalDateTime;

public record UserAccount(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String targetRole,
        List<String> skillTags,
        AccountType accountType,
        LocalDateTime demoExpiresAt
) {

    public UserAccount(Long id, String username, String passwordHash, String displayName,
                       String targetRole, List<String> skillTags) {
        this(id, username, passwordHash, displayName, targetRole, skillTags, AccountType.STANDARD, null);
    }

    public boolean isDemo() {
        return accountType == AccountType.DEMO_TEMPLATE || accountType == AccountType.DEMO_SANDBOX;
    }

    public boolean isExpiredDemoSandbox(LocalDateTime now) {
        return accountType == AccountType.DEMO_SANDBOX
                && demoExpiresAt != null
                && !demoExpiresAt.isAfter(now);
    }

    public String publicUsername() {
        return isDemo() ? "demo" : username;
    }
}
