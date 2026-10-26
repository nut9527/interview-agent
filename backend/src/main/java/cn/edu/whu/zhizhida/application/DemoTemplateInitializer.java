package cn.edu.whu.zhizhida.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoTemplateInitializer implements ApplicationRunner {

    private final DemoTemplateService demoTemplateService;
    private final boolean enabled;

    public DemoTemplateInitializer(DemoTemplateService demoTemplateService,
                                   @Value("${demo.enabled:true}") boolean enabled) {
        this.demoTemplateService = demoTemplateService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) {
            demoTemplateService.ensureTemplate();
        }
    }
}
