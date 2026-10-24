package ru.self.printlab_backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Базовые геометрические характеристики STL-модели.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelGeometry {

    private double volumeMm3;
    private double surfaceAreaMm2;
    private double boundingBoxXmm;
    private double boundingBoxYmm;
    private double boundingBoxZmm;
    private int triangleCount;

    /** Габаритный объём параллелепипеда, мм^3. */
    public double boundingBoxVolumeMm3() {
        return boundingBoxXmm * boundingBoxYmm * boundingBoxZmm;
    }
}