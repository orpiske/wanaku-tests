package ai.wanaku.test.router;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Disabled("DataStore not native in praxis")
class DataStoreCliITCase extends RouterTestBase {

    @DisplayName("Add a data store entry via CLI and verify it exists via REST")
    @Test
    void shouldAddDataStoreEntryViaCli() {}

    @DisplayName("Upload 3 entries via REST and verify all appear in CLI list output")
    @Test
    void shouldListDataStoreEntriesViaCli() {}

    @DisplayName("Upload an entry via REST, get via CLI, and verify content in output")
    @Test
    void shouldGetDataStoreEntryViaCli() {}

    @DisplayName("Upload an entry via REST, remove via CLI, and verify removal")
    @Test
    void shouldRemoveDataStoreEntryViaCli() {}
}
