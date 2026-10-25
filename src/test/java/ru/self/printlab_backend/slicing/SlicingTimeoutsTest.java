package ru.self.printlab_backend.slicing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlicingTimeoutsTest {
    @Test void rejectsBudgetsThatCanOutliveTheHttpRequestOrKillNativeWorkTooEarly() {
        for (long[] limits : new long[][] {{180, 45, 0}, {180, 45, 45}, {30, 45, 20}, {181, 45, 40}}) {
            assertThatThrownBy(() -> new SlicingTimeouts(limits[0], limits[1], limits[2]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
