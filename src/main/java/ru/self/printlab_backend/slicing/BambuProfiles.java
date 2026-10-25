package ru.self.printlab_backend.slicing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import static ru.self.printlab_backend.slicing.PrintPlanException.Error.SLICER_UNAVAILABLE;

/** Resolves the installed vendor profiles without copying or editing the user's presets. */
@Component
public class BambuProfiles {
    private final JsonMapper mapper;
    private final Path root;
    private final Map<String, Map<String, Object>> cache = new HashMap<>();

    public BambuProfiles(JsonMapper mapper, @Value("${printlab.bambu.profiles:/Applications/BambuStudio.app/Contents/Resources/profiles/BBL}") String root) {
        this.mapper = mapper;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public synchronized Map<String, Object> load(String kind, String name) {
        String key = kind + "/" + name;
        if (cache.containsKey(key)) return new LinkedHashMap<>(cache.get(key));
        try {
            Map<String, Object> value = resolve(kind, name, new HashSet<>());
            cache.put(key, Map.copyOf(value));
            return new LinkedHashMap<>(value);
        } catch (IOException | RuntimeException e) {
            throw new PrintPlanException(SLICER_UNAVAILABLE, e);
        }
    }

    private Map<String, Object> resolve(String kind, String name, Set<Path> chain) throws IOException {
        Path directory = root.resolve(kind);
        Path path = directory.resolve(name + ".json").normalize();
        if (!path.startsWith(directory) || !chain.add(path) || chain.size() > 20) throw new IOException("Invalid profile inheritance");
        if (!Files.isRegularFile(path)) throw new IOException("Profile not installed: " + path);
        Map<String, Object> data = mapper.readValue(Files.readAllBytes(path), new TypeReference<LinkedHashMap<String, Object>>() {});
        Map<String, Object> result = new LinkedHashMap<>();
        if (data.get("inherits") instanceof String parent && !parent.isBlank()) result.putAll(resolve(kind, parent, chain));
        if (data.get("include") instanceof List<?> includes) {
            for (Object include : includes) result.putAll(resolve(kind, (String) include, chain));
        }
        result.putAll(data);
        result.remove("inherits");
        result.remove("include");
        chain.remove(path);
        return result;
    }
}
