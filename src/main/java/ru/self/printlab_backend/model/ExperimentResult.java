package ru.self.printlab_backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentResult {

    private String id;
    private String modelId;
    private String material;

    private double infillPercent;
    private String infillPattern; 
    private int perimeters;
    private double layerHeightMm;
    private String printOrientation;

    private double compressiveStrengthMpa;
    private double wallThicknessMm;
    private double nozzleTemperatureC;
    private double printSpeedMmS;
    private double printTimeMin;
    private double materialConsumptionG;

    /** Измеренная разрушающая нагрузка, Н (в таблице кг, переведено). */
    private Double failureLoadN;
    
    /** Тип испытания: изгиб, сжатие и т.д. */
    private String testType;
}