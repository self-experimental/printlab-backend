package ru.self.printlab_backend.dto.printplan;

import java.util.List;

public record PrintPlanResponse(String printerName, double nozzleDiameterMm, String bedType,
        String materialName, String engineVersion, String selectedCandidateId, int plannedCandidateCount,
        List<Candidate> candidates, List<String> warnings, String explanation, Recommendation recommendation) {
    public record Candidate(String id, int infillPercent, int perimeters, double layerHeightMm, String infillPattern,
            double estimatedPrintTimeMin, double estimatedMaterialG, boolean meetsConstraints,
            int topLayers, int bottomLayers, PrintPlanRequest.SupportMode supportMode) {}
    public record Recommendation(String source, String purpose, String summary, List<Item> items) {
        public Recommendation { items = List.copyOf(items); }
    }
    public record Item(String title, String text) {}
}
