package io.github.jnesew.plainpoddroid.core.artwork;

import java.util.Objects;

public final class ArtworkPayload {
    public enum Format { JPEG, PNG, WEBP }

    private final Format format;
    private final byte[] bytes;

    ArtworkPayload(Format format, byte[] bytes) {
        this(format, bytes, false);
    }

    private ArtworkPayload(Format format, byte[] bytes, boolean owned) {
        this.format = Objects.requireNonNull(format, "format");
        this.bytes = owned ? Objects.requireNonNull(bytes, "bytes")
                : Objects.requireNonNull(bytes, "bytes").clone();
    }

    // Only the acquirer's newly allocated, unshared result may transfer ownership.
    static ArtworkPayload owned(Format format, byte[] bytes) { return new ArtworkPayload(format, bytes, true); }

    public Format getFormat() { return format; }
    public byte[] getBytes() { return bytes.clone(); }
    public java.io.InputStream openStream() { return new java.io.ByteArrayInputStream(bytes); }
    public int size() { return bytes.length; }
}
