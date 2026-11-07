package cn.edu.whu.zhizhida.interview.execution;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelRetryPolicyTest {
    @Test void onlyTransientFailuresAreRetried() {
        for (String code : new String[]{"MODEL_NETWORK", "MODEL_HTTP_429", "MODEL_HTTP_500",
                "MODEL_HTTP_502", "MODEL_HTTP_503", "MODEL_HTTP_504"})
            assertThat(ModelRetryPolicy.retryable(code)).isTrue();
        for (String code : new String[]{"MODEL_HTTP_400", "MODEL_HTTP_401", "MODEL_HTTP_307",
                "MODEL_INTERRUPTED", "MODEL_PROTOCOL", "RUN_BUDGET", "MODEL_ACTION_INVALID"})
            assertThat(ModelRetryPolicy.retryable(code)).isFalse();
    }

    @Test void backoffIsStableBoundedAndCannotOverflow() {
        for (int ordinal : new int[]{1, 2, 3, 4, 30, Integer.MAX_VALUE}) {
            long base = ordinal < 4 ? 1_000L << (ordinal - 1) : 8_000L;
            long delay = ModelRetryPolicy.delayMillis("run", ordinal);
            assertThat(delay).isBetween(base, base + 250);
            assertThat(ModelRetryPolicy.delayMillis("run", ordinal)).isEqualTo(delay);
        }
        assertThatThrownBy(() -> ModelRetryPolicy.delayMillis("run", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
