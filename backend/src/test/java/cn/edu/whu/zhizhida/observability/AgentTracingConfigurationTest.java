package cn.edu.whu.zhizhida.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class AgentTracingConfigurationTest {
    @Test void actualApplicationDefaultsDisableExportButKeepLocalTraceCorrelation() {
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("${AGENT_TRACE_EXPORT_ENABLED:false}");
        assertThat(properties.getProperty("management.logging.export.otlp.enabled")).isEqualTo("false");
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                OpenTelemetrySdkAutoConfiguration.class, OpenTelemetryTracingAutoConfiguration.class,
                OtlpTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class))
                .withPropertyValues("management.tracing.export.otlp.enabled=false",
                        "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:1/v1/traces")
                .run(context -> {
                    assertThat(context).hasSingleBean(Tracer.class).doesNotHaveBean(SpanExporter.class);
                    var telemetry = new AgentTelemetry(context.getBean(Tracer.class));
                    try (var span = telemetry.start(AgentTelemetry.Stage.ACCEPT)) { assertThat(span.parent()).isNotNull(); }
                });
    }
}
