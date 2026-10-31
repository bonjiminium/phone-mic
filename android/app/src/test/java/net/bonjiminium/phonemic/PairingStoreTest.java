package net.bonjiminium.phonemic;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PairingStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void startsUnpairedAndPersistsOnlyAfterExplicitSave() throws Exception {
        Path directory = temporary.newFolder().toPath();
        PairingStore store = new PairingStore(directory);
        assertNull(store.load());
        PairingConfig parsed = PairingConfigTest.example("https://pc.local");
        assertNull(store.load()); // Parsing/scanning alone never changes saved pairing.
        store.save(parsed);
        assertEquals(parsed.serialize(), new PairingStore(directory).load().serialize());
    }

    @Test public void reparingReplacesAddressAndPinWithoutChangingApkAndForgetClears() throws Exception {
        PairingStore store = new PairingStore(temporary.newFolder().toPath());
        store.save(PairingConfigTest.example("https://old.local"));
        PairingConfig updated = PairingConfig.parse(PairingConfigTest.qr("https://new.local:8765", "z".repeat(32), "ef".repeat(32), 1));
        store.save(updated);
        assertEquals(updated.serialize(), store.load().serialize());
        store.clear();
        assertNull(store.load());
        store.clear(); // idempotent
    }

    @Test public void corruptStorageFailsClosedAndDoesNotRegenerateCredentials() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path file = directory.resolve("receiver-pairing.json");
        Files.write(file, "corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PairingStore store = new PairingStore(directory);
        assertThrows(IllegalArgumentException.class, store::load);
        assertEquals("corrupt", new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
        store.save(PairingConfigTest.example("https://pc.local"));
        assertNotNull(store.load());
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }
}
