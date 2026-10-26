package ru.self.printlab_backend.recommendation;

public interface PurposeClassifier {
    PartPurpose classify(String description);
}
