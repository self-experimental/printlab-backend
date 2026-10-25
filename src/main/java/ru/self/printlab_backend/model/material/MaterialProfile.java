package ru.self.printlab_backend.model.material;

import java.util.List;

public record MaterialProfile(
        String id, String family, String name, String manufacturer, Source source,
        Printing printing, double densityGcm3, String densityMethod,
        Double hdtAt045MpaC, Double hdtAt180MpaC,
        List<TestSeries> testSeries, List<String> notes) {
    public MaterialProfile {
        testSeries = List.copyOf(testSeries);
        notes = List.copyOf(notes);
    }
    public record Source(String title, String url, String version, String publishedOn,
                         String verifiedOn, List<Integer> pages, String sha256) {
        public Source { pages = List.copyOf(pages); }
    }
    public record Range(double min, double max) {}
    public record Printing(Range nozzleTemperatureC, Range bedTemperatureC,
                           double maxPrintSpeedMmS, Range coolingFanPercent,
                           Boolean hardenedNozzleRequired) {}
    public enum SpecimenType { PRINTED, INJECTION_MOLDED }
    public enum Direction { HORIZONTAL, VERTICAL_XZ, UNSPECIFIED }
    public enum Property { TENSILE_YIELD_STRENGTH, TENSILE_STRENGTH, TENSILE_MODULUS, FLEXURAL_STRENGTH }
    public record Measurement(Property property, double value, Double uncertainty,
                              String unit, String method, Direction direction) {}
    public record Conditions(String printer, String slicer, double layerHeightMm, int perimeters,
                             double infillPercent, String infillPattern, int topLayers, int bottomLayers,
                             double infillPrintSpeedMmS, double nozzleTemperatureC, double bedTemperatureC,
                             Double nozzleDiameterMm, Double extrusionMultiplier, Double coolingFanPercent,
                             List<String> notes) {
        public Conditions { notes = List.copyOf(notes); }
    }
    public record TestSeries(String id, SpecimenType specimenType, String specimenDescription,
                             Conditions conditions, List<Measurement> measurements) {
        public TestSeries { measurements = List.copyOf(measurements); }
    }
}
