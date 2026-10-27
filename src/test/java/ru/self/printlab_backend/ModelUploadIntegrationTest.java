package ru.self.printlab_backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.self.printlab_backend.dto.model.UploadedModel;
import ru.self.printlab_backend.repository.FileSystemModelRepository;
import ru.self.printlab_backend.stl.StlLimits;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class ModelUploadIntegrationTest {
    @TempDir static Path storage;
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper mapper;
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("printlab.models.dir", () -> storage.toString());
    }

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test
    void uploadsBothEncodingsAndReadsMetadataFromNewRepository() throws Exception {
        for (byte[] content : new byte[][] {StlFileParserTest.ASCII.getBytes(StandardCharsets.UTF_8), StlFileParserTest.binary()}) {
            var file = new MockMultipartFile("file", "../деталь.STL", "application/octet-stream", content);
            var result = mvc.perform(multipart("/api/models").file(file))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.fileName").value("деталь.STL"))
                    .andExpect(jsonPath("$.triangleCount").value(1)).andReturn();
            UploadedModel model = mapper.readValue(result.getResponse().getContentAsByteArray(), UploadedModel.class);
            assertEquals("/api/models/" + model.id(), result.getResponse().getHeader("Location"));
            assertEquals(content.length, model.sizeBytes());
            assertArrayEquals(content, Files.readAllBytes(storage.resolve(model.id().toString()).resolve("model.stl")));
            var reopened = new FileSystemModelRepository(mapper, storage.toString());
            assertEquals(model, reopened.find(model.id()));
            mvc.perform(get("/api/models/{id}", model.id()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(model.id().toString()));
        }
        try (var files = Files.list(storage)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".upload-")));
        }
    }

    @Test
    void rejectsInvalidUploadsWithoutCreatingFiles() throws Exception {
        long before;
        try (var paths = Files.list(storage)) { before = paths.count(); }
        for (var file : new MockMultipartFile[] {
                new MockMultipartFile("file", "empty.stl", "application/octet-stream", new byte[0]),
                new MockMultipartFile("file", "fake.stl", "application/octet-stream", "broken".getBytes()),
                new MockMultipartFile("file", "wrong.txt", "text/plain", StlFileParserTest.binary())}) {
            mvc.perform(multipart("/api/models").file(file))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        }
        mvc.perform(multipart("/api/models").file(new MockMultipartFile("file", "huge.stl",
                        "application/octet-stream", new byte[StlLimits.MAX_BYTES + 1])))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.status").value(413));
        try (var paths = Files.list(storage)) { assertEquals(before, paths.count()); }
    }

    @Test
    void reportsMissingFileUnsupportedMediaAndInvalidOrUnknownId() throws Exception {
        mvc.perform(multipart("/api/models")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/models").contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/models/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/models/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void keepsExistingAnalysisEndpointWorkingForAscii() throws Exception {
        mvc.perform(multipart("/api/models/analyze").file(new MockMultipartFile("file", "example.stl",
                        "application/octet-stream", StlFileParserTest.ASCII.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.geometry.triangleCount").value(1));
    }

    @Test
    void storageFailureCannotPublishPartialModel() throws Exception {
        var repository = new FileSystemModelRepository(mapper, storage.toString());
        Path collision = storage.resolve("not-a-directory");
        Files.writeString(collision, "occupied");
        assertThrows(IllegalStateException.class, () -> new FileSystemModelRepository(mapper, collision.toString()));
        UUID id = UUID.randomUUID();
        Path directory = storage.resolve(id.toString());
        Files.createDirectory(directory);
        Files.writeString(directory.resolve("metadata.json"), "invalid json");
        Files.write(directory.resolve("model.stl"), StlFileParserTest.binary());
        assertThrows(RuntimeException.class, () -> repository.find(id));
        mvc.perform(get("/api/models/{id}", id)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("На сервере произошла ошибка. Повторите попытку немного позже."))
                .andExpect(jsonPath("$.code").value("SERVER_ERROR"));
        Files.delete(directory.resolve("metadata.json"));
        Files.delete(directory.resolve("model.stl"));
        Files.delete(directory);
        Files.delete(collision);
    }

    @Test
    void failedCommitCleansStagingAndPreservesExistingDirectory() throws Exception {
        var repository = new FileSystemModelRepository(mapper, storage.toString());
        UUID id = UUID.randomUUID();
        Path existing = storage.resolve(id.toString());
        Files.createDirectory(existing);
        Files.writeString(existing.resolve("sentinel"), "unchanged");
        var model = new UploadedModel(id, "triangle.stl", 134, 1, Instant.now());
        assertThrows(IllegalStateException.class, () -> repository.save(StlFileParserTest.binary(), model));
        assertEquals("unchanged", Files.readString(existing.resolve("sentinel")));
        assertFalse(Files.exists(existing.resolve("metadata.json")));
        try (var paths = Files.list(storage)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".upload-")));
        }
        Files.delete(existing.resolve("sentinel"));
        Files.delete(existing);
    }

    @Test
    void acceptsZeroAreaFacesSoTheNativeSlicerCanRepairThem() throws Exception {
        String broken = StlFileParserTest.ASCII.replace("vertex 2 0 0", "vertex 0 0 0");
        mvc.perform(multipart("/api/models").file(new MockMultipartFile("file", "broken.stl",
                        "application/octet-stream", broken.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.triangleCount").value(1));
    }
}
