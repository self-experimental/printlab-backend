package ru.self.printlab_backend.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Repository;
import ru.self.printlab_backend.model.material.MaterialCatalog;
import ru.self.printlab_backend.model.material.MaterialProfile;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.HexFormat;

/** Loaded once. Invalid evidence prevents startup rather than silently losing the catalog. */
@Repository
public class MaterialCatalogRepository {
    private final MaterialCatalog catalog;
    private final String etag;

    public MaterialCatalogRepository(JsonMapper mapper,
            @Value("${printlab.materials.resource:classpath:catalog/materials.json}") Resource resource) {
        try (var stream = resource.getInputStream()) {
            byte[] bytes = stream.readAllBytes();
            catalog = mapper.readValue(bytes, MaterialCatalog.class);
            validate(catalog);
            etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\"";
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            throw new IllegalStateException("Не удалось загрузить проверенный каталог материалов", e);
        }
    }

    public MaterialCatalog catalog() { return catalog; }
    public String etag() { return etag; }

    static void validate(MaterialCatalog catalog) {
        require(catalog.schemaVersion() == 1 && !catalog.materials().isEmpty());
        var ids = new HashSet<String>();
        for (var material : catalog.materials()) {
            require(ids.add(material.id()) && !material.id().isBlank());
            require(!material.family().isBlank() && !material.name().isBlank() && !material.manufacturer().isBlank());
            require(positive(material.densityGcm3()) && !material.densityMethod().isBlank());
            var source = material.source();
            URI uri = URI.create(source.url());
            require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null);
            require(!source.title().isBlank() && !source.version().isBlank() && !source.pages().isEmpty());
            require(source.sha256() != null && source.sha256().matches("[0-9a-f]{64}"));
            require(source.pages().stream().allMatch(p -> p > 0));
            LocalDate verified = LocalDate.parse(source.verifiedOn());
            LocalDate published = source.publishedOn().length() == 7
                    ? YearMonth.parse(source.publishedOn()).atDay(1) : LocalDate.parse(source.publishedOn());
            require(!published.isAfter(verified));
            var printing = material.printing();
            range(printing.nozzleTemperatureC(), 0, 500);
            range(printing.bedTemperatureC(), 0, 250);
            range(printing.coolingFanPercent(), 0, 100);
            require(positive(printing.maxPrintSpeedMmS()));
            require(material.hdtAt045MpaC() == null || positive(material.hdtAt045MpaC()));
            require(material.hdtAt180MpaC() == null || positive(material.hdtAt180MpaC()));
            require(!material.testSeries().isEmpty());
            var seriesIds = new HashSet<String>();
            for (var series : material.testSeries()) {
                require(seriesIds.add(series.id()) && !series.specimenDescription().isBlank());
                require(series.specimenType() != null && !series.measurements().isEmpty());
                if (series.specimenType() == MaterialProfile.SpecimenType.PRINTED) {
                    var conditions = series.conditions();
                    require(conditions != null && positive(conditions.layerHeightMm()) && conditions.perimeters() > 0);
                    require(Double.isFinite(conditions.infillPercent()) && conditions.infillPercent() >= 0 && conditions.infillPercent() <= 100);
                    require(!conditions.printer().isBlank() && !conditions.slicer().isBlank());
                    require(positive(conditions.infillPrintSpeedMmS()) && positive(conditions.nozzleTemperatureC())
                            && positive(conditions.bedTemperatureC()));
                }
                var measurements = new HashSet<String>();
                for (var measurement : series.measurements()) {
                    require(measurement.property() != null && measurement.direction() != null);
                    require(measurements.add(measurement.property() + ":" + measurement.direction()));
                    require(positive(measurement.value()) && "MPa".equals(measurement.unit()) && !measurement.method().isBlank());
                    require(measurement.uncertainty() == null || (Double.isFinite(measurement.uncertainty()) && measurement.uncertainty() >= 0));
                }
            }
        }
    }

    private static void range(MaterialProfile.Range range, double min, double max) {
        require(Double.isFinite(range.min()) && Double.isFinite(range.max())
                && range.min() >= min && range.max() <= max && range.min() <= range.max());
    }
    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Некорректные данные или источник материала");
    }
}
