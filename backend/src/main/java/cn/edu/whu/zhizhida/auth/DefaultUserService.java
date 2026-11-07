package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.AccountLifecycle;

import cn.edu.whu.zhizhida.auth.api.ChangePasswordRequest;
import cn.edu.whu.zhizhida.auth.api.DeleteAccountRequest;
import cn.edu.whu.zhizhida.auth.api.UserProfile;
import cn.edu.whu.zhizhida.auth.api.UserProfileRequest;
import cn.edu.whu.zhizhida.auth.api.UserService;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DefaultUserService implements UserService, AccountLifecycle {

    private final UserAccountRepository userRepository;
    private final SessionRepository sessionRepository;
    private final CurrentUserProvider currentUserProvider;
    private final PasswordEncoder passwordEncoder;

    public DefaultUserService(UserAccountRepository userRepository,
                              SessionRepository sessionRepository,
                              CurrentUserProvider currentUserProvider,
                              PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.currentUserProvider = currentUserProvider;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserProfile current() {
        return toProfile(currentAccount());
    }

    @Override
    public UserProfile update(UserProfileRequest request) {
        UserProfileRequest normalized = new UserProfileRequest(
                request.displayName().trim(),
                request.targetRole().trim(),
                normalizeSkillTags(request.skillTags())
        );
        return toProfile(userRepository.updateProfile(currentUserId(), normalized));
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        UserAccount account = currentAccount();
        requireStandardAccount(account);
        verifyPassword(request.currentPassword(), account);
        if (passwordEncoder.matches(request.newPassword(), account.passwordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同");
        }
        userRepository.updatePassword(account.id(), passwordEncoder.encode(request.newPassword()));
        sessionRepository.deleteByUserId(account.id());
    }

    @Override
    @Transactional
    public Long authorizeClosure(DeleteAccountRequest request) {
        userRepository.lockById(currentUserId()); // 通过账号锁协调注销授权、数据清理与后台任务的准入和结果提交。
        UserAccount account = currentAccount();
        requireStandardAccount(account);
        verifyPassword(request.currentPassword(), account);
        return account.id();
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void deleteIdentity(Long userId) {
        sessionRepository.deleteByUserId(userId);
        if (!userRepository.deleteById(userId)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "登录状态已失效");
        }
    }

    private UserAccount currentAccount() {
        return userRepository.findById(currentUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "登录状态已失效"));
    }

    private Long currentUserId() {
        return currentUserProvider.requireUserId();
    }

    private void verifyPassword(String rawPassword, UserAccount account) {
        if (!passwordEncoder.matches(rawPassword, account.passwordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
    }

    private void requireStandardAccount(UserAccount account) {
        if (account.isDemo()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "演示空间不支持修改密码或注销账户");
        }
    }

    private List<String> normalizeSkillTags(List<String> skillTags) {
        Map<String, String> unique = new LinkedHashMap<>();
        if (skillTags != null) {
            skillTags.stream()
                    .map(String::trim)
                    .filter(tag -> !tag.isEmpty())
                    .forEach(tag -> unique.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag));
        }
        return List.copyOf(unique.values());
    }

    private UserProfile toProfile(UserAccount account) {
        return new UserProfile(account.id(), account.publicUsername(), account.displayName(),
                account.targetRole(), account.skillTags(), account.isDemo());
    }
}
