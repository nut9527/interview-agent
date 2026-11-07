package cn.edu.whu.zhizhida.interview.execution;

import org.springframework.scheduling.annotation.Scheduled;

public class RunRecoveryScheduler {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RunRecoveryScheduler.class);
    private final InterviewRunStore store;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private volatile double lastSuccess = Double.NaN;
    public RunRecoveryScheduler(InterviewRunStore store, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.store=store;
        this.metrics=metrics;
        metrics.gauge("zhizhida.run.recovery.last.success.seconds", this, value -> value.lastSuccess);
    }
    @Scheduled(scheduler="runScheduler",fixedDelayString="${app.run-recovery.interval-ms:5000}")
    public void recover() {
        var sample=io.micrometer.core.instrument.Timer.start(metrics);
        try {
            store.recoverDispatches();
            lastSuccess=System.currentTimeMillis()/1000.0;
            metrics.counter("zhizhida.run.recovery", "outcome", "success").increment();
        } catch (RuntimeException ex) {
            metrics.counter("zhizhida.run.recovery", "outcome", "unavailable").increment();
            log.warn("event=run.recovery outcome=unavailable errorType={}",ex.getClass().getSimpleName());
        } finally { sample.stop(metrics.timer("zhizhida.run.recovery.duration")); }
    }
}
