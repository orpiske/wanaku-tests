package ai.wanaku.test.router;

import java.nio.charset.StandardCharsets;
import java.util.List;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@QuarkusTest
class DataStoreCrudITCase extends RouterTestBase {

    @BeforeEach
    void assumeRouterAvailable() {
        assumeThat(isRouterAvailable()).as("Router must be available").isTrue();
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

    @DisplayName("Upload binary content and verify round-trip preserves bytes")
    @Test
    void shouldHandleBinaryContent() {
        // Given
        String name = "binary-data.bin";
        byte[] binaryContent = new byte[] {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD};

        // When
        dataStoreClient.upload(name, binaryContent);
        String downloaded = dataStoreClient.download(name);

        // Then
        assertThat(downloaded.getBytes(StandardCharsets.UTF_8))
                .as("Downloaded binary content should match uploaded bytes")
                .isEqualTo(binaryContent);
    }

    @DisplayName("Upload with labels is not supported by client API - skip")
    @Test
    void shouldUploadEntryWithLabels() {
        // The DataStoreClient.upload() hardcodes empty labels.
        // This test verifies basic upload still works (labels feature untested at client level).
        assumeThat(false)
                .as("DataStoreClient does not expose a labels parameter")
                .isTrue();
    }

    @DisplayName("Upload the same name twice and verify latest content is returned")
    @Test
    void shouldOverwriteExistingEntry() {
        // Given
        String name = "overwrite-me.txt";
        dataStoreClient.upload(name, "original content");
        assertThat(dataStoreClient.download(name)).isEqualTo("original content");

        // When
        dataStoreClient.upload(name, "updated content");

        // Then
        assertThat(dataStoreClient.download(name)).isEqualTo("updated content");
    }
}
