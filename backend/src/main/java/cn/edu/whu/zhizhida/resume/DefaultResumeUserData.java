package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeUserData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DefaultResumeUserData implements ResumeUserData {
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;

    public DefaultResumeUserData(ResumeRepository resumeRepository, ResumeVersionRepository resumeVersionRepository) {
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
    }

    @Override
    public void initializeDemoTemplate(Long templateUserId) {
        if (!resumeRepository.findAll(templateUserId).isEmpty()) {
            return;
        }
        ResumeProfile saved = resumeRepository.save(templateUserId, new ResumeProfile(
                null,
                "演示同学",
                "Java 后端实习生",
                "武汉大学软件工程学生，熟悉 Spring Boot、MySQL 与 Vue3，正在寻找后端开发实习机会。",
                List.of("Java", "Spring Boot", "MySQL", "Redis", "Vue3", "Docker"),
                List.of("2024-2028 武汉大学 软件工程 本科，主修数据结构、数据库系统、软件工程实践。"),
                List.of(
                        "AI 智能简历与求职助手：负责 Spring Boot 后端接口、简历结构化管理、岗位匹配服务和 Docker 联调，完成 8 个 REST 接口并沉淀接口文档。",
                        "校园活动管理系统：实现活动发布、报名签到、数据统计模块，使用 MySQL 设计 6 张核心表，支持 200+ 条演示数据查询。"
                ),
                List.of("武汉某科技公司后端实习：参与业务接口开发和接口测试，修复 12 个联调问题。"),
                List.of("CET-6", "软件设计师备考", "GitLab CI 实践"),
                "classic"
        ));
        resumeVersionRepository.save(templateUserId, saved, "TEMPLATE", "公共演示模板初始版本");
    }

    @Override
    public void copyDemoResumes(Long templateUserId, Long sandboxUserId) {
        for (ResumeProfile source : resumeRepository.findAll(templateUserId)) {
            ResumeProfile cloned = resumeRepository.save(sandboxUserId, new ResumeProfile(null,
                    source.ownerName(), source.targetRole(), source.summary(), source.skills(),
                    source.educationExperiences(), source.projectExperiences(), source.internshipExperiences(),
                    source.certificates(), source.templateKey(), 0L, source.details()));
            resumeVersionRepository.save(sandboxUserId, cloned, "TEMPLATE", "从公共演示模板复制");
        }
    }

    @Override
    public void deleteOwnedData(Long userId) { resumeRepository.deleteAllByUserId(userId); }
}
