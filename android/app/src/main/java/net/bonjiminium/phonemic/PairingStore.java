package net.bonjiminium.phonemic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Pass Context.getNoBackupFilesDir(): app-private and excluded from backups. */
public final class PairingStore {
    private final Path directory;
    private final Path file;
    public PairingStore(Path directory) {
        this.directory = directory;
        this.file = directory.resolve("receiver-pairing.json");
    }

    public PairingConfig load() throws IOException {
        if (!Files.exists(file)) return null;
        if (Files.size(file) > PairingConfig.MAX_LENGTH) throw new IOException("Saved pairing is too large");
        return PairingConfig.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    public void save(PairingConfig config) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".pairing-", ".tmp");
        try {
            Files.write(temporary, config.serialize().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public void clear() throws IOException { Files.deleteIfExists(file); }
}
