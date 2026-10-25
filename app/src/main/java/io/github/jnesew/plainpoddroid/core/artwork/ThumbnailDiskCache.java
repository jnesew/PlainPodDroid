package io.github.jnesew.plainpoddroid.core.artwork;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Private, disposable thumbnail bytes. URLs never become filenames or file contents. */
public final class ThumbnailDiskCache {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final int MAX_ENTRIES = 512;
    public static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final int MAGIC = 0x50505431;
    private static final int HEADER_BYTES = 16;
    private final Path root;
    private final long budget;
    private final int entries;

    public record Entry(byte[] bytes, long savedAtMillis) { }

    public ThumbnailDiskCache(Path directory) throws IOException { this(directory, MAX_BYTES, MAX_ENTRIES); }
    public ThumbnailDiskCache(Path directory, long budget, int entries) throws IOException {
        if (budget < HEADER_BYTES + 1 || budget > MAX_BYTES || entries < 1 || entries > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid thumbnail quota.");
        }
        root = directory.toAbsolutePath().normalize(); this.budget = budget; this.entries = entries;
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid cache directory.");
        trim();
    }
    public static String key(String url, int targetPixels) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (targetPixels + "\n" + url).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte b : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private Path file(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cache key.");
        return root.resolve(key + ".thumb");
    }
    public synchronized Entry get(String key, long now) throws IOException {
        Path file = file(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        long size = Files.size(file);
        if (size <= HEADER_BYTES || size > MAX_ENTRY_BYTES + HEADER_BYTES) { Files.deleteIfExists(file); return null; }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            int magic = input.readInt(); long savedAt = input.readLong(); int count = input.readInt();
            if (magic != MAGIC || savedAt < 0 || count < 1 || count > MAX_ENTRY_BYTES || size != count + HEADER_BYTES) {
                Files.deleteIfExists(file); return null;
            }
            byte[] bytes = new byte[count]; input.readFully(bytes);
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(Math.max(0, now)));
            return new Entry(bytes, savedAt);
        } catch (java.io.EOFException invalid) { Files.deleteIfExists(file); return null; }
    }
    public synchronized void put(String key, byte[] bytes, long now) throws IOException {
        Path destination = file(key);
        if (bytes == null || bytes.length < 1 || bytes.length > MAX_ENTRY_BYTES || now < 0
                || bytes.length + HEADER_BYTES > budget) throw new IllegalArgumentException("Invalid thumbnail entry.");
        Path temporary = Files.createTempFile(root, "thumbnail-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeInt(MAGIC); output.writeLong(now); output.writeInt(bytes.length); output.write(bytes);
            }
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setLastModifiedTime(destination, java.nio.file.attribute.FileTime.fromMillis(now)); trim();
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized void remove(String key) throws IOException { Files.deleteIfExists(file(key)); }
    private void trim() throws IOException {
        record Stored(Path path, long size, long accessed) { }
        List<Stored> files = new ArrayList<>(); long total = 0; int scanned = 0;
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
            for (Path path : directory) {
                if (++scanned > 2048) throw new IOException("Cache directory exceeds inspection limit.");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = path.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}\\.thumb")) {
                    if (name.startsWith("thumbnail-") && name.endsWith(".tmp")
                            && System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() > 3_600_000) {
                        Files.deleteIfExists(path);
                    }
                    continue;
                }
                long size = Files.size(path);
                if (size <= HEADER_BYTES || size > MAX_ENTRY_BYTES + HEADER_BYTES) { Files.deleteIfExists(path); continue; }
                files.add(new Stored(path, size, Files.getLastModifiedTime(path).toMillis())); total += size;
            }
        }
        files.sort(Comparator.comparingLong(Stored::accessed).thenComparing(s -> s.path().toString()));
        int remaining = files.size();
        for (Stored stored : files) {
            if (total <= budget && remaining <= entries) break;
            Files.deleteIfExists(stored.path()); total -= stored.size(); remaining--;
        }
    }
}
