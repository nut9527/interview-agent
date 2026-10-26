package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.auth.api.AuthResponse;
import cn.edu.whu.zhizhida.auth.api.DemoAccounts;
import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.resume.api.ResumeUserData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为每次演示登录创建独立且有期限的沙箱，并复制模板简历及版本。
 * 身份创建、数据复制和会话签发共用事务，失败时不留下半初始化的演示空间。
 */
@Service
public class DemoSandboxService {
    private final DemoAccounts accounts;
    private final ResumeUserData resumes;
    private final int ttlHours;
    private final int maxActiveSandboxes;
    private final boolean enabled;

    public DemoSandboxService(DemoAccounts accounts, ResumeUserData resumes,
            @Value("${demo.sandbox-ttl-hours:4}") int ttlHours,
            @Value("${demo.max-active-sandboxes:100}") int maxActiveSandboxes,
            @Value("${demo.enabled:true}") boolean enabled) {
        this.accounts = accounts;
        this.resumes = resumes;
        this.ttlHours = Math.max(1, ttlHours);
        this.maxActiveSandboxes = Math.max(1, maxActiveSandboxes);
        this.enabled = enabled;
    }

    @Transactional
    public AuthResponse demoLogin() {
        if (!enabled) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "演示登录当前未启用");
        // 认证模块取得的容量锁持续到本事务提交，使容量检查覆盖后续复制和会话写入。
        var sandbox = accounts.createSandbox(ttlHours, maxActiveSandboxes);
        resumes.initializeDemoTemplate(sandbox.templateUserId());
        resumes.copyDemoResumes(sandbox.templateUserId(), sandbox.userId());
        return accounts.openSession(sandbox);
    }
}
