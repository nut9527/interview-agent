package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.auth.api.DemoAccounts;
import cn.edu.whu.zhizhida.resume.api.ResumeUserData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoTemplateService {
    private final DemoAccounts accounts;
    private final ResumeUserData resumes;

    public DemoTemplateService(DemoAccounts accounts, ResumeUserData resumes) {
        this.accounts = accounts;
        this.resumes = resumes;
    }

    @Transactional
    public void ensureTemplate() { resumes.initializeDemoTemplate(accounts.ensureTemplate()); }
}
