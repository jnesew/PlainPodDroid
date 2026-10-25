package io.github.jnesew.plainpoddroid.core.download;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AppPrivateDownloadStore implements PlaybackDownloadStore {
    public static final long UNKNOWN_LENGTH = -1L;

    private static final int MARKER_MAGIC = 0x50504431; // PPD1
    public static final long COMPLETION_MARKER_BYTES = Integer.BYTES + Long.BYTES;
    private static final String PREFIX = "ep-";
    private static final String COMPLETE_SUFFIX = ".audio";
    private static final String PARTIAL_SUFFIX = ".part";
    private static final String MARKER_SUFFIX = ".complete";
    private static final String MARKER_TEMP_SUFFIX = ".complete.part";
    private static final Pattern COMPLETE_FILE_PATTERN =
            Pattern.compile("ep-[0-9a-f]{64}\\.audio");
    private static final Pattern CONTROLLED_FILE_PATTERN =
            Pattern.compile("ep-[0-9a-f]{64}\\.(audio|part|complete|complete\\.part)");

    private final File directory;
    private final File canonicalDirectory;
    private final long maxDownloadBytes;
    private final Set<String> activeKeys = new HashSet<>();
    private final Map<String, Integer> readers = new HashMap<>();

    public AppPrivateDownloadStore(File directory) throws IOException {
        this(directory, SecurityLimits.MAX_DOWNLOAD_BYTES);
    }

    public AppPrivateDownloadStore(File directory, long maxDownloadBytes) throws IOException {
        if (maxDownloadBytes <= 0L) {
            throw new IllegalArgumentException("maxDownloadBytes must be positive");
        }
        this.directory = directory.getAbsoluteFile();
        this.maxDownloadBytes = maxDownloadBytes;
        Files.createDirectories(this.directory.toPath());
        if (Files.isSymbolicLink(this.directory.toPath())
                || !Files.isDirectory(this.directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Download directory must be a non-symbolic directory");
        }
        this.canonicalDirectory = this.directory.getCanonicalFile();
    }

    @Override
    public DownloadLookup lookup(String mediaId) throws IOException {
        String key = opaqueKey(mediaId);
        return lookupByKey(key);
    }

    @Override
    public synchronized boolean isVerifiedCompleteUri(String uriText) throws IOException {
        if (uriText == null || uriText.length() > SecurityLimits.MAX_URL_CHARS) {
            return false;
        }

        final URI uri;
        try {
            uri = new URI(uriText);
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return false;
        }
        if (!"file".equals(uri.getScheme())
                || uri.getRawAuthority() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return false;
        }

        final File candidate;
        try {
            candidate = new File(uri).getAbsoluteFile();
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!COMPLETE_FILE_PATTERN.matcher(candidate.getName()).matches()) {
            return false;
        }
        if (!hasExpectedParent(candidate)) {
            return false;
        }

        String key = candidate.getName().substring(
                0,
                candidate.getName().length() - COMPLETE_SUFFIX.length());
        return validateComplete(candidate, markerFile(key));
    }

    @Override
    public synchronized Closeable acquireReadLease(String uriText) throws IOException {
        if (!isVerifiedCompleteUri(uriText)) {
            throw new IOException("Playback source is not a verified complete download");
        }
        File file = new File(URI.create(uriText));
        String key = file.getName().substring(0,
                file.getName().length() - COMPLETE_SUFFIX.length());
        if (activeKeys.contains(key)) {
            throw new IOException("Download is being replaced");
        }
        readers.put(key, Math.addExact(readers.getOrDefault(key, 0), 1));
        return new Closeable() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (AppPrivateDownloadStore.this) {
                    if (closed) return;
                    closed = true;
                    int remaining = readers.get(key) - 1;
                    if (remaining == 0) readers.remove(key);
                    else readers.put(key, remaining);
                }
            }
        };
    }

    public synchronized DownloadWriter begin(String mediaId, long expectedLength)
            throws IOException {
        return begin(mediaId, expectedLength, maxDownloadBytes);
    }

    public synchronized DownloadWriter begin(
            String mediaId,
            long expectedLength,
            long maximumWriteBytes) throws IOException {
        if (maximumWriteBytes <= 0L || maximumWriteBytes > maxDownloadBytes) {
            throw new IllegalArgumentException("Write limit is outside the download limit");
        }
        if (expectedLength != UNKNOWN_LENGTH
                && (expectedLength <= 0L || expectedLength > maximumWriteBytes)) {
            throw new IllegalArgumentException("Expected length is outside the download limit");
        }

        String key = opaqueKey(mediaId);
        if (readers.containsKey(key)) {
            throw new IOException("Cannot replace a download in use for playback");
        }
        if (!activeKeys.add(key)) {
            throw new IOException("A download for this media item is already active");
        }

        try {
            assertDirectoryStillPrivate();
            return new DownloadWriter(
                    key,
                    expectedLength,
                    maximumWriteBytes,
                    partialFile(key),
                    completeFile(key),
                    markerFile(key),
                    markerTempFile(key));
        } catch (IOException | RuntimeException exception) {
            activeKeys.remove(key);
            throw exception;
        }
    }

    public synchronized void delete(String mediaId) throws IOException {
        String key = opaqueKey(mediaId);
        if (readers.containsKey(key)) throw new DownloadInUseException(true);
        if (activeKeys.contains(key)) throw new DownloadInUseException(false);
        assertDirectoryStillPrivate();
        Files.deleteIfExists(partialFile(key).toPath());
        Files.deleteIfExists(markerTempFile(key).toPath());
        Files.deleteIfExists(markerFile(key).toPath());
        Files.deleteIfExists(completeFile(key).toPath());
    }

    public synchronized int cleanupAbandonedTemporaryFiles(long nowMillis)
            throws IOException {
        return cleanupAbandonedTemporaryFiles(
                nowMillis,
                SecurityLimits.MAX_DOWNLOAD_DIRECTORY_ENTRIES);
    }

    synchronized int cleanupAbandonedTemporaryFiles(
            long nowMillis,
            int maximumEntries) throws IOException {
        if (nowMillis < 0L) {
            throw new IllegalArgumentException("nowMillis must not be negative");
        }
        if (maximumEntries <= 0
                || maximumEntries > SecurityLimits.MAX_DOWNLOAD_DIRECTORY_ENTRIES) {
            throw new IllegalArgumentException("maximumEntries is outside the storage limit");
        }
        assertDirectoryStillPrivate();
        long expirationThreshold = nowMillis - SecurityLimits.DOWNLOAD_TEMP_MAX_AGE_MILLIS;
        int inspectedEntries = 0;
        int deletedEntries = 0;
        try (var entries = Files.newDirectoryStream(directory.toPath())) {
            for (Path entry : entries) {
                inspectedEntries++;
                if (inspectedEntries > maximumEntries) {
                    throw new IOException("Download directory contains too many entries");
                }
                String name = entry.getFileName().toString();
                assertControlledRegularFile(entry, name);
                String temporaryKey = temporaryKey(name);
                if (temporaryKey != null
                        && !activeKeys.contains(temporaryKey)
                        && Files.getLastModifiedTime(
                                entry,
                                LinkOption.NOFOLLOW_LINKS).toMillis() <= expirationThreshold) {
                    Files.delete(entry);
                    deletedEntries++;
                }
            }
        }
        return deletedEntries;
    }

    public synchronized long storageUsageBytes() throws IOException {
        assertDirectoryStillPrivate();
        long total = 0L;
        int inspectedEntries = 0;
        try (var entries = Files.newDirectoryStream(directory.toPath())) {
            for (Path entry : entries) {
                inspectedEntries++;
                if (inspectedEntries > SecurityLimits.MAX_DOWNLOAD_DIRECTORY_ENTRIES) {
                    throw new IOException("Download directory contains too many entries");
                }
                String name = entry.getFileName().toString();
                assertControlledRegularFile(entry, name);
                total = Math.addExact(total, Files.size(entry));
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Download storage usage overflowed", exception);
        }
        return total;
    }

    private static void assertControlledRegularFile(Path entry, String name)
            throws IOException {
        if (!CONTROLLED_FILE_PATTERN.matcher(name).matches()
                || Files.isSymbolicLink(entry)
                || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Download directory contains invalid material");
        }
    }

    private static String temporaryKey(String name) {
        if (name.endsWith(MARKER_TEMP_SUFFIX)) {
            return name.substring(0, name.length() - MARKER_TEMP_SUFFIX.length());
        }
        if (name.endsWith(PARTIAL_SUFFIX)) {
            return name.substring(0, name.length() - PARTIAL_SUFFIX.length());
        }
        return null;
    }

    private DownloadLookup lookupByKey(String key) throws IOException {
        assertDirectoryStillPrivate();
        File complete = completeFile(key);
        File marker = markerFile(key);
        if (validateComplete(complete, marker)) {
            return DownloadLookup.complete(complete);
        }

        boolean hasCompleteMaterial = existsWithoutFollowingLinks(complete)
                || existsWithoutFollowingLinks(marker);
        if (hasCompleteMaterial) {
            return DownloadLookup.withoutFile(DownloadLookup.State.INVALID);
        }
        if (existsWithoutFollowingLinks(partialFile(key))) {
            return DownloadLookup.withoutFile(DownloadLookup.State.PARTIAL);
        }
        return DownloadLookup.withoutFile(DownloadLookup.State.ABSENT);
    }

    private boolean validateComplete(File complete, File marker) throws IOException {
        Path completePath = complete.toPath();
        Path markerPath = marker.toPath();
        if (Files.isSymbolicLink(completePath)
                || Files.isSymbolicLink(markerPath)
                || !Files.isRegularFile(completePath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)
                || !hasExpectedParent(complete)
                || !hasExpectedParent(marker)
                || marker.length() != COMPLETION_MARKER_BYTES) {
            return false;
        }

        long recordedLength;
        try (DataInputStream input = new DataInputStream(new FileInputStream(marker))) {
            if (input.readInt() != MARKER_MAGIC) {
                return false;
            }
            recordedLength = input.readLong();
            if (input.read() != -1) {
                return false;
            }
        } catch (EOFException exception) {
            return false;
        }

        long actualLength = complete.length();
        return recordedLength > 0L
                && recordedLength <= maxDownloadBytes
                && actualLength == recordedLength;
    }

    private void assertDirectoryStillPrivate() throws IOException {
        Path path = directory.toPath();
        if (Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !directory.getCanonicalFile().equals(canonicalDirectory)) {
            throw new IOException("Download directory identity changed");
        }
    }

    private boolean hasExpectedParent(File file) throws IOException {
        File parent = file.getParentFile();
        return parent != null
                && parent.getCanonicalFile().equals(canonicalDirectory)
                && file.getCanonicalFile().getParentFile().equals(canonicalDirectory);
    }

    private static boolean existsWithoutFollowingLinks(File file) {
        Path path = file.toPath();
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path);
    }

    private File partialFile(String key) {
        return new File(directory, key + PARTIAL_SUFFIX);
    }

    private File completeFile(String key) {
        return new File(directory, key + COMPLETE_SUFFIX);
    }

    private File markerFile(String key) {
        return new File(directory, key + MARKER_SUFFIX);
    }

    private File markerTempFile(String key) {
        return new File(directory, key + MARKER_TEMP_SUFFIX);
    }

    private static String opaqueKey(String mediaId) {
        if (mediaId == null
                || mediaId.trim().isEmpty()
                || mediaId.length() > SecurityLimits.MAX_IDENTIFIER_CHARS) {
            throw new IllegalArgumentException("Invalid media ID");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(mediaId.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(PREFIX.length() + hash.length * 2);
            result.append(PREFIX);
            for (byte value : hash) {
                result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java platform", exception);
        }
    }

    private static void atomicMove(File source, File destination) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("App-private storage does not support atomic moves", exception);
        }
    }

    private synchronized void release(String key) {
        activeKeys.remove(key);
    }

    public final class DownloadWriter implements Closeable {
        private final String key;
        private final long expectedLength;
        private final long maximumWriteBytes;
        private final File partial;
        private final File complete;
        private final File marker;
        private final File markerTemp;
        private FileOutputStream output;
        private long writtenBytes;
        private boolean failed;
        private boolean finished;
        private boolean closed;

        private DownloadWriter(
                String key,
                long expectedLength,
                long maximumWriteBytes,
                File partial,
                File complete,
                File marker,
                File markerTemp) throws IOException {
            this.key = key;
            this.expectedLength = expectedLength;
            this.maximumWriteBytes = maximumWriteBytes;
            this.partial = partial;
            this.complete = complete;
            this.marker = marker;
            this.markerTemp = markerTemp;
            if (Files.isSymbolicLink(partial.toPath())) {
                throw new IOException("Partial download path is symbolic");
            }
            output = new FileOutputStream(partial, false);
        }

        public void write(byte[] bytes) throws IOException {
            write(bytes, 0, bytes.length);
        }

        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureOpen();
            if (bytes == null
                    || offset < 0
                    || length < 0
                    || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException("Invalid write range");
            }
            if (length > maximumWriteBytes - writtenBytes
                    || (expectedLength != UNKNOWN_LENGTH
                    && length > expectedLength - writtenBytes)) {
                failed = true;
                throw new IOException("Download exceeds its permitted byte count");
            }
            output.write(bytes, offset, length);
            writtenBytes += length;
        }

        public void finish() throws IOException {
            ensureOpen();
            if (failed || writtenBytes <= 0L
                    || (expectedLength != UNKNOWN_LENGTH && writtenBytes != expectedLength)) {
                close();
                throw new IOException("Download is not complete");
            }

            try {
                output.getFD().sync();
                output.close();
                output = null;
                atomicMove(partial, complete);
                writeCompletionMarker(writtenBytes);
                atomicMove(markerTemp, marker);
                finished = true;
            } finally {
                closed = true;
                if (output != null) {
                    output.close();
                    output = null;
                }
                release(key);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (output != null) {
                    output.close();
                    output = null;
                }
            } finally {
                release(key);
            }
        }

        public boolean isFinished() {
            return finished;
        }

        private void ensureOpen() throws IOException {
            if (closed || finished || output == null) {
                throw new IOException("Download writer is closed");
            }
        }

        private void writeCompletionMarker(long length) throws IOException {
            if (Files.isSymbolicLink(markerTemp.toPath())) {
                throw new IOException("Completion marker path is symbolic");
            }
            FileOutputStream fileOutput = new FileOutputStream(markerTemp, false);
            try (DataOutputStream dataOutput = new DataOutputStream(fileOutput)) {
                dataOutput.writeInt(MARKER_MAGIC);
                dataOutput.writeLong(length);
                dataOutput.flush();
                fileOutput.getFD().sync();
            }
        }
    }
}
