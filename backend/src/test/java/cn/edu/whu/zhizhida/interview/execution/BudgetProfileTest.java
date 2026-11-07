package cn.edu.whu.zhizhida.interview.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BudgetProfileTest {
    private final JsonMapper json = new JsonMapper();

    @ParameterizedTest
    @ValueSource(strings = {"B0", "S1", "S3"})
    void supportedProfilesRetainFrozenLimits(String name) {
        var profile = BudgetProfile.production(name);
        assertThat(profile.execution().maxSteps()).isEqualTo(name.equals("S1") ? 1 : 3);
        assertThat(profile.execution().maxModelAttempts()).isEqualTo(4);
        assertThat(profile.execution().deadlineMillis()).isEqualTo(90_000);
        assertThat(profile.tokens()).isNull();
        assertThat(profile.attemptLimit("ACTION", false)).isEqualTo(2);
        assertThat(profile.attemptLimit("FINAL", false)).isEqualTo(3);
        assertThat(profile.attemptLimit("FINAL", true)).isEqualTo(4);
        assertThat(profile.actionMinimumMillis()).isEqualTo(56_000);
        assertThat(json.readTree(json.writeValueAsString(profile))).isEqualTo(json.readTree("""
                {"version":"budget-v1","execution":{"version":"%s","maxSteps":%d,
                 "maxModelAttempts":4,"maxToolBatches":2,"maxToolExecutions":3,"maxRepairs":1,
                 "deadlineMillis":90000,"callMillis":25000},"tokens":null,"finalRequests":2,"finalCallMillis":25000}
                """.formatted(name, name.equals("S1") ? 1 : 3)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTERACTIVE", "DEEP_REVIEW", "unknown"})
    void unavailableProfilesAreRejected(String name) {
        assertThatThrownBy(() -> BudgetProfile.production(name)).hasMessageContaining("not enabled");
    }

    @Test void oldBaselineKeepsItsActualLimitsAndHasNoInventedTokenBudget() {
        var p = BudgetProfile.production("B0");
        assertThat(p.tokens()).isNull();
        assertThat(p.callTimeout("LEGACY", false, 20_000)).isEqualTo(15_000);
        assertThat(p.callTimeout("FINAL", false, 50_000)).isEqualTo(20_000);
        assertThatThrownBy(() -> p.callTimeout("FINAL", false, 30_000)).hasMessage("RUN_BUDGET");
        assertThatThrownBy(() -> p.reservedRequests("invented", false)).hasMessage("RUN_CHECKPOINT_INVALID");
    }

    @Test void snapshotMustMatchVersionAndValuesButOldConfigurationsRemainReadable() {
        var config = json.createObjectNode().put("loopPolicy", "S1");
        assertThat(BudgetProfile.frozen(json, config).execution().maxSteps()).isEqualTo(1);
        config.set("budgetProfile", json.valueToTree(BudgetProfile.production("S1")));
        assertThat(BudgetProfile.frozen(json, config)).isEqualTo(BudgetProfile.production("S1"));
        assertThat(BudgetProfile.frozen(json, json.readTree(config.toString()))).isEqualTo(BudgetProfile.production("S1"));
        config.withObject("budgetProfile").withObject("execution").put("maxModelAttempts", 5);
        assertThatThrownBy(() -> BudgetProfile.frozen(json, config)).hasMessage("MODEL_CONFIG_CHANGED");
        config.remove("budgetProfile");
        config.put("loopPolicy", "DEEP_REVIEW");
        assertThatThrownBy(() -> BudgetProfile.frozen(json, config)).hasMessage("MODEL_CONFIG_CHANGED");
        config.put("loopPolicy", "unknown");
        assertThatThrownBy(() -> BudgetProfile.frozen(json, config)).hasMessage("MODEL_CONFIG_CHANGED");
    }

}
