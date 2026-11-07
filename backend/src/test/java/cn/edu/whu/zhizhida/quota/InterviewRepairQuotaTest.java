package cn.edu.whu.zhizhida.quota;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterviewRepairQuotaTest {
    @Test void correctionConsumesGlobalResourcesWithoutChargingUserMinuteOrDailyAllowanceAgain() {
        var quotas = mock(RequestQuotaService.class);
        new AiRequestQuota(quotas, 5, 40, 400).admitInterviewRepair();
        @SuppressWarnings("unchecked") ArgumentCaptor<List<RequestQuotaService.Charge>> charges = ArgumentCaptor.forClass(List.class);
        verify(quotas).admit(charges.capture());
        assertThat(charges.getValue()).singleElement().satisfies(charge -> {
            assertThat(charge.scope()).isEqualTo("ai-global-day");
            assertThat(charge.subject()).isEqualTo("all");
            assertThat(charge.units()).isEqualTo(2);
            assertThat(charge.maxUnits()).isEqualTo(400);
        });
    }
}
