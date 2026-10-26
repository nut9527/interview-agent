package cn.edu.whu.zhizhida.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AiRequestQuota {
    // 准入权重不等于模型令牌数；准入成功后，即使模型调用失败也保留额度消耗。
    public static final int RESUME_OPTIMIZATION_UNITS = 2;
    public static final int INTERVIEW_START_UNITS = 3;
    public static final int INTERVIEW_REVIEW_UNITS = 2;
    public static final int INTERVIEW_REPAIR_GLOBAL_UNITS = 2;
    private final RequestQuotaService quotas;
    private final int perMinute;
    private final int unitsPerDay;
    private final int globalUnitsPerDay;

    public AiRequestQuota(RequestQuotaService quotas,
            @Value("${quota.ai.per-minute:5}") int perMinute,
            @Value("${quota.ai.units-per-day:40}") int unitsPerDay,
            @Value("${quota.ai.global-units-per-day:400}") int globalUnitsPerDay) {
        this.quotas = quotas;
        this.perMinute = perMinute;
        this.unitsPerDay = unitsPerDay;
        this.globalUnitsPerDay = globalUnitsPerDay;
    }

    public void admit(String userId, int reservedWorkUnits) {
        quotas.admit(List.of(
                new RequestQuotaService.Charge("ai-minute", userId, 60, perMinute, perMinute, 1,
                        "AI 生成过于频繁，请稍后重试"),
                new RequestQuotaService.Charge("ai-day", userId, 86400, Integer.MAX_VALUE, unitsPerDay, reservedWorkUnits,
                        "今日 AI 生成额度已用完"),
                new RequestQuotaService.Charge("ai-global-day", "all", 86400, Integer.MAX_VALUE, globalUnitsPerDay, reservedWorkUnits,
                        "今日 AI 生成总额度已用完")));
    }

    /** A bounded correction is covered by the original user admission; provider cost still uses the global cap. */
    public void admitInterviewRepair() {
        quotas.admit(List.of(new RequestQuotaService.Charge("ai-global-day", "all", 86400, Integer.MAX_VALUE,
                globalUnitsPerDay, INTERVIEW_REPAIR_GLOBAL_UNITS, "今日 AI 生成总额度已用完")));
    }
}
