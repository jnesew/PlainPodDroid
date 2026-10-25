package io.github.jnesew.plainpoddroid.core.security;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads source fixtures on the host without depending on Android's Files.readString API. */
final class SourceFileReader {
    private SourceFileReader() {}

    static String readString(Path path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    static String readString(Path path, Charset charset) throws IOException {
        return charset.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString();
    }
}
