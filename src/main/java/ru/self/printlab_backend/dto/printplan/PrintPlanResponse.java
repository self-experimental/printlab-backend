package ru.self.printlab_backend.dto.printplan;

import java.util.List;

public record PrintPlanResponse(String printerName, double nozzleDiameterMm, String bedType,
        String materialName, String engineVersion, String selectedCandidateId, int plannedCandidateCount,
        List<Candidate> candidates, List<String> warnings, String explanation) {
    public record Candidate(String id, int infillPercent, int perimeters, double layerHeightMm, String infillPattern,
            double estimatedPrintTimeMin, double estimatedMaterialG, boolean meetsConstraints) {}
}
