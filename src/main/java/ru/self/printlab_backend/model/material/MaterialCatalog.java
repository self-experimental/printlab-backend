package ru.self.printlab_backend.model.material;

import java.util.List;

/** Reference data; never interpreted as a measured failure load for an uploaded part. */
public record MaterialCatalog(int schemaVersion, List<MaterialProfile> materials) {
    public MaterialCatalog { materials = List.copyOf(materials); }
}
