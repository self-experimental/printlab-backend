package ru.self.printlab_backend.stl;

import ru.self.printlab_backend.model.ModelGeometry;

/** Constant-memory geometry calculation shared by both STL encodings. */
final class StlGeometryAccumulator {
    private final double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    private final double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
    private int count;
    private double volume;
    private double area;

    void add(double[] p) {
        if (++count > StlLimits.MAX_TRIANGLES) {
            throw new StlParseException(StlUploadError.TOO_MANY_TRIANGLES);
        }
        for (int i = 0; i < p.length; i++) {
            if (!Double.isFinite(p[i]) || Math.abs(p[i]) > StlLimits.MAX_COORDINATE) {
                throw new StlParseException(StlUploadError.INVALID_COORDINATES);
            }
            min[i % 3] = Math.min(min[i % 3], p[i]);
            max[i % 3] = Math.max(max[i % 3], p[i]);
        }
        double ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
        double bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
        double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
        double triangleArea = 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (triangleArea == 0) {
            throw new StlParseException(StlUploadError.DEGENERATE_FACE);
        }
        area += triangleArea;
        volume += (p[0] * (p[4] * p[8] - p[5] * p[7])
                - p[1] * (p[3] * p[8] - p[5] * p[6])
                + p[2] * (p[3] * p[7] - p[4] * p[6])) / 6;
    }

    ModelGeometry result() {
        if (count == 0) throw new StlParseException(StlUploadError.EMPTY_MODEL);
        return ModelGeometry.builder()
                .triangleCount(count).volumeMm3(Math.abs(volume)).surfaceAreaMm2(area)
                .boundingBoxXmm(max[0] - min[0]).boundingBoxYmm(max[1] - min[1])
                .boundingBoxZmm(max[2] - min[2]).build();
    }
}
