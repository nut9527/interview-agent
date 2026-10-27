package cn.edu.whu.zhizhida.observability;

import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BusinessMetricsConfig {
    @Bean
    MeterRegistryCustomizer<MeterRegistry> boundedHttpMetrics() {
        return registry -> registry.config().meterFilter(
                MeterFilter.maximumAllowableTags("http.server.requests", "uri", 100, MeterFilter.deny()));
    }
}
