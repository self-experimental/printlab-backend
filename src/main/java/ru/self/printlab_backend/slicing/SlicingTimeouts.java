package ru.self.printlab_backend.slicing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The synchronous API is bounded by a 200-second proxy and a 210-second client. */
@Component
public record SlicingTimeouts(
        @Value("${printlab.slicing.job-timeout-seconds:180}") long jobSeconds,
        @Value("${printlab.slicing.process-timeout-seconds:45}") long processSeconds,
        @Value("${printlab.slicing.native-timeout-seconds:40}") long nativeSeconds) {
    public SlicingTimeouts {
        if (nativeSeconds < 1 || nativeSeconds >= processSeconds
                || processSeconds > jobSeconds || jobSeconds > 180) {
            throw new IllegalArgumentException("Slicing timeouts must satisfy 1 <= native < process <= job <= 180 seconds");
        }
    }
}
