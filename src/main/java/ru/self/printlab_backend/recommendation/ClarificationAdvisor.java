package ru.self.printlab_backend.recommendation;

import java.util.Optional;

@FunctionalInterface
public interface ClarificationAdvisor {
    Optional<String> suggest(String description);
}
