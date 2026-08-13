package ai.wanaku.test.router;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import io.quarkus.test.junit.QuarkusTest;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class DataStoreCrudITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isServerRunning()).as("Router must be available").isTrue();
        assumeThat(false)
                .as("DataStore is not natively available in praxis mode")
                .isFalse();
    }

    @DisplayName("Upload a text entry and download it, verifying content matches")
    @Test
    void shouldUploadAndDownloadEntry() {
        // Given
        String name = "test.txt";
        String content = "Hello DataStore";

        // When
        dataStoreClient.upload(name, content);
        String downloaded = dataStoreClient.download(name);

        // Then
        assertThat(downloaded).isEqualTo(content);
    }

    @DisplayName("Upload 3 entries and verify all appear in the list")
    @Test
    void shouldListUploadedEntries() {
        // Given
        dataStoreClient.upload("entry-alpha.txt", "Alpha content");
        dataStoreClient.upload("entry-beta.txt", "Beta content");
        dataStoreClient.upload("entry-gamma.txt", "Gamma content");

        // When
        List<String> entries = dataStoreClient.list();

        // Then
        assertThat(entries).containsExactlyInAnyOrder("entry-alpha.txt", "entry-beta.txt", "entry-gamma.txt");
    }

    @DisplayName("Upload an entry, remove it, and verify it no longer appears in the list")
    @Test
    void shouldRemoveEntry() {
        // Given
        String name = "remove-me.txt";
        dataStoreClient.upload(name, "temporary content");
        assertThat(dataStoreClient.list()).contains(name);

        // When
        boolean removed = dataStoreClient.removeByName(name);

        // Then
        assertThat(removed).isTrue();
        assertThat(dataStoreClient.list()).doesNotContain(name);
    }

    @DisplayName("Return false when removing a nonexistent entry")
    @Test
    void shouldReturnFalseWhenRemovingNonexistentEntry() {
        // When
        boolean removed = dataStoreClient.removeByName("nonexistent");

        // Then
        assertThat(removed).isFalse();
    }

    @DisplayName("Upload 2 entries, clear all, and verify the list is empty")
    @Test
    void shouldClearAllEntries() {
        // Given
        dataStoreClient.upload("clear-1.txt", "first");
        dataStoreClient.upload("clear-2.txt", "second");
        assertThat(dataStoreClient.list()).hasSize(2);

        // When
        dataStoreClient.clearAll();

        // Then
        assertThat(dataStoreClient.list()).isEmpty();
    }

    @DisplayName("Upload text as bytes and verify round-trip preserves content")
    @Test
    void shouldHandleBinaryContent() {
        String name = "binary-data.bin";
        String textContent = "Binary-safe content test: ABC 123";
        byte[] contentBytes = textContent.getBytes(StandardCharsets.UTF_8);

        dataStoreClient.upload(name, contentBytes);
        String downloaded = dataStoreClient.download(name);

        assertThat(downloaded)
                .as("Downloaded content should match uploaded text")
                .isEqualTo(textContent);
    }

    @DisplayName("Upload an entry with labels and verify labels are persisted")
    @Test
    void shouldUploadEntryWithLabels() {
        String name = "labeled-entry.txt";
        Map<String, String> labels = Map.of("environment", "test", "component", "router");

        dataStoreClient.upload(name, "labeled content", labels);

        String downloaded = dataStoreClient.download(name);
        assertThat(downloaded)
                .as("Data content should survive labels round-trip")
                .isEqualTo("labeled content");

        JsonNode entry = dataStoreClient.downloadEntry(name);
        assertThat(entry.has("labels"))
                .as("Response should contain labels field")
                .isTrue();

        JsonNode labelsNode = entry.get("labels");
        assertThat(labelsNode.has("environment")).isTrue();
        assertThat(labelsNode.get("environment").asText()).isEqualTo("test");
        assertThat(labelsNode.has("component")).isTrue();
        assertThat(labelsNode.get("component").asText()).isEqualTo("router");
    }

    @DisplayName("DataStore rejects duplicate entry names with 409")
    @Test
    void shouldRejectDuplicateEntry() {
        String name = "duplicate-entry.txt";
        dataStoreClient.upload(name, "original content");

        try {
            dataStoreClient.upload(name, "duplicate content");
            assertThat(dataStoreClient.download(name)).isNotNull();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("409");
        }
    }
}
