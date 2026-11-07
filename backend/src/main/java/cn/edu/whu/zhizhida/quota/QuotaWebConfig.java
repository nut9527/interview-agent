package cn.edu.whu.zhizhida.quota;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class QuotaWebConfig implements WebMvcConfigurer {
    private final RequestQuotaInterceptor interceptor;

    public QuotaWebConfig(RequestQuotaInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
