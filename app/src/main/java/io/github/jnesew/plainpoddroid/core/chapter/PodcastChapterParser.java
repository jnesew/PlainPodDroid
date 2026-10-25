package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Dependency-free, streaming parser for bounded Podcasting 2.0 JSON chapters. */
public final class PodcastChapterParser {
    private static final Pattern SUPPORTED_VERSION =
            Pattern.compile("1(?:\\.[0-9]+){1,2}");
    private static final BigDecimal MILLIS_PER_SECOND = BigDecimal.valueOf(1_000L);
    private static final BigDecimal MAX_SECONDS = BigDecimal.valueOf(
            SecurityLimits.MAX_MEDIA_DURATION_MILLIS,
            3);

    public PodcastChapterDocument parse(InputStream input)
            throws IOException, ChapterParseException {
        Objects.requireNonNull(input, "input");
        Reader reader = new InputStreamReader(
                new BoundedInputStream(input, SecurityLimits.MAX_CHAPTER_BYTES),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT));
        JsonCursor cursor = new JsonCursor(reader);
        try {
            PodcastChapterDocument document = parseDocument(cursor);
            cursor.requireEndOfInput();
            return document;
        } catch (LimitExceededIOException exception) {
            throw new ChapterParseException(
                    ChapterParseException.Reason.INPUT_LIMIT,
                    "Chapter document exceeds its byte limit.",
                    exception);
        } catch (CharacterCodingException exception) {
            throw new ChapterParseException(
                    ChapterParseException.Reason.MALFORMED_JSON,
                    "Chapter document is not valid UTF-8.",
                    exception);
        }
    }

    private static PodcastChapterDocument parseDocument(JsonCursor cursor)
            throws IOException, ChapterParseException {
        cursor.beginObject();
        String version = null;
        List<PodcastChapter> chapters = null;
        boolean first = true;
        while (cursor.nextCompositeValue(first, '}')) {
            first = false;
            String name = cursor.readName();
            if (name.equals("version")) {
                if (version != null) {
                    throw schema("Duplicate chapter-document version.");
                }
                version = cursor.readString(SecurityLimits.MAX_CHAPTER_VERSION_CHARS);
            } else if (name.equals("chapters")) {
                if (chapters != null) {
                    throw schema("Duplicate chapters array.");
                }
                chapters = parseChapters(cursor);
            } else {
                cursor.skipValue();
            }
        }
        if (version == null || chapters == null) {
            throw schema("Chapter document requires version and chapters.");
        }
        if (!SUPPORTED_VERSION.matcher(version).matches()) {
            throw new ChapterParseException(
                    ChapterParseException.Reason.UNSUPPORTED_VERSION,
                    "Unsupported JSON chapters version.");
        }
        return new PodcastChapterDocument(version, chapters);
    }

    private static List<PodcastChapter> parseChapters(JsonCursor cursor)
            throws IOException, ChapterParseException {
        cursor.beginArray();
        List<PodcastChapter> chapters = new ArrayList<>();
        long previousStart = -1L;
        boolean first = true;
        while (cursor.nextCompositeValue(first, ']')) {
            first = false;
            if (chapters.size() >= SecurityLimits.MAX_CHAPTERS) {
                throw new ChapterParseException(
                        ChapterParseException.Reason.CHAPTER_LIMIT,
                        "Chapter document exceeds its chapter-count limit.");
            }
            PodcastChapter chapter = parseChapter(cursor);
            if (chapter.getStartTimeMillis() < previousStart) {
                throw schema("Chapter start times are not ascending.");
            }
            previousStart = chapter.getStartTimeMillis();
            chapters.add(chapter);
        }
        return chapters;
    }

    private static PodcastChapter parseChapter(JsonCursor cursor)
            throws IOException, ChapterParseException {
        cursor.beginObject();
        BigDecimal startSeconds = null;
        BigDecimal endSeconds = null;
        String title = "";
        CanonicalHttpsUrl imageUrl = null;
        CanonicalHttpsUrl websiteUrl = null;
        boolean visible = true;
        boolean titleSeen = false;
        boolean imageSeen = false;
        boolean websiteSeen = false;
        boolean visibleSeen = false;
        boolean endSeen = false;
        boolean first = true;
        while (cursor.nextCompositeValue(first, '}')) {
            first = false;
            String name = cursor.readName();
            switch (name) {
                case "startTime" -> {
                    if (startSeconds != null) {
                        throw schema("Duplicate chapter startTime.");
                    }
                    startSeconds = cursor.readNumber();
                }
                case "endTime" -> {
                    if (endSeen) {
                        throw schema("Duplicate chapter endTime.");
                    }
                    endSeen = true;
                    endSeconds = cursor.readNumber();
                }
                case "title" -> {
                    if (titleSeen) {
                        throw schema("Duplicate chapter title.");
                    }
                    titleSeen = true;
                    title = normalizeTitle(cursor.readString(
                            SecurityLimits.MAX_CHAPTER_TITLE_CHARS));
                }
                case "img" -> {
                    if (imageSeen) {
                        throw schema("Duplicate chapter image URL.");
                    }
                    imageSeen = true;
                    imageUrl = parseHttpsUrl(cursor.readString(SecurityLimits.MAX_URL_CHARS));
                }
                case "url" -> {
                    if (websiteSeen) {
                        throw schema("Duplicate chapter website URL.");
                    }
                    websiteSeen = true;
                    websiteUrl = parseHttpsUrl(cursor.readString(SecurityLimits.MAX_URL_CHARS));
                }
                case "toc" -> {
                    if (visibleSeen) {
                        throw schema("Duplicate chapter toc flag.");
                    }
                    visibleSeen = true;
                    visible = cursor.readBoolean();
                }
                default -> cursor.skipValue();
            }
        }
        if (startSeconds == null) {
            throw schema("Chapter requires startTime.");
        }
        long startMillis = timeMillis(startSeconds, "startTime");
        long endMillis = endSeconds == null
                ? PodcastChapter.UNKNOWN_END_TIME_MILLIS
                : timeMillis(endSeconds, "endTime");
        if (endMillis != PodcastChapter.UNKNOWN_END_TIME_MILLIS
                && endMillis < startMillis) {
            throw schema("Chapter endTime precedes startTime.");
        }
        return new PodcastChapter(
                startMillis,
                endMillis,
                title,
                imageUrl,
                websiteUrl,
                visible);
    }

    private static long timeMillis(BigDecimal seconds, String field)
            throws ChapterParseException {
        if (seconds.signum() < 0 || seconds.compareTo(MAX_SECONDS) > 0) {
            throw schema("Chapter " + field + " is outside the media-duration bound.");
        }
        // Tiny exponents can have a billion-digit scale despite a short JSON
        // token. Values below half a millisecond round to zero; decide that
        // without asking BigDecimal to construct an enormous power of ten.
        if (seconds.compareTo(BigDecimal.valueOf(5L, 4)) < 0) return 0L;
        try {
            return seconds.multiply(MILLIS_PER_SECOND)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw schema("Chapter " + field + " is not a supported finite time.");
        }
    }

    private static CanonicalHttpsUrl parseHttpsUrl(String value)
            throws ChapterParseException {
        if (value.isEmpty()) {
            throw schema("Chapter URL must not be empty.");
        }
        try {
            return CanonicalHttpsUrl.parse(value);
        } catch (NetworkPolicyException exception) {
            throw schema("Chapter URL violates the HTTPS policy.");
        }
    }

    private static String normalizeTitle(String value) throws ChapterParseException {
        StringBuilder output = new StringBuilder(value.length());
        boolean pendingSpace = false;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!Character.isValidCodePoint(codePoint)
                    || codePoint >= Character.MIN_SURROGATE
                    && codePoint <= Character.MAX_SURROGATE) {
                throw schema("Chapter title contains invalid Unicode.");
            }
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                pendingSpace = output.length() != 0;
                continue;
            }
            if (pendingSpace) {
                output.append(' ');
                pendingSpace = false;
            }
            output.appendCodePoint(codePoint);
            if (output.length() > SecurityLimits.MAX_CHAPTER_TITLE_CHARS) {
                throw fieldLimit("Chapter title exceeds its normalized limit.");
            }
        }
        String normalized = Normalizer.normalize(output, Normalizer.Form.NFC);
        if (normalized.length() > SecurityLimits.MAX_CHAPTER_TITLE_CHARS) {
            throw fieldLimit("Chapter title exceeds its normalized limit.");
        }
        return normalized;
    }

    private static ChapterParseException schema(String message) {
        return new ChapterParseException(
                ChapterParseException.Reason.INVALID_SCHEMA,
                message);
    }

    private static ChapterParseException fieldLimit(String message) {
        return new ChapterParseException(
                ChapterParseException.Reason.FIELD_LIMIT,
                message);
    }

    private static final class JsonCursor {
        private static final int EMPTY = -2;

        private final Reader reader;
        private int lookahead = EMPTY;
        private int depth;
        private int tokens;

        JsonCursor(Reader reader) {
            this.reader = reader;
        }

        void beginObject() throws IOException, ChapterParseException {
            token();
            expect('{');
            enterComposite();
        }

        void beginArray() throws IOException, ChapterParseException {
            token();
            expect('[');
            enterComposite();
        }

        boolean nextCompositeValue(boolean first, char closing)
                throws IOException, ChapterParseException {
            skipWhitespace();
            if (peek() == closing) {
                take();
                depth--;
                return false;
            }
            if (!first) {
                expect(',');
                skipWhitespace();
                if (peek() == closing) {
                    throw malformed("Trailing JSON comma.");
                }
            }
            return true;
        }

        String readName() throws IOException, ChapterParseException {
            token();
            String name = readStringBody(SecurityLimits.MAX_CHAPTER_JSON_KEY_CHARS);
            skipWhitespace();
            expect(':');
            return name;
        }

        String readString(int maxChars) throws IOException, ChapterParseException {
            token();
            return readStringBody(maxChars);
        }

        BigDecimal readNumber() throws IOException, ChapterParseException {
            token();
            skipWhitespace();
            StringBuilder number = new StringBuilder();
            if (peek() == '-') {
                appendNumber(number, take());
            }
            int first = peek();
            if (first == '0') {
                appendNumber(number, take());
                if (isDigit(peek())) {
                    throw malformed("JSON number has a leading zero.");
                }
            } else if (first >= '1' && first <= '9') {
                do {
                    appendNumber(number, take());
                } while (isDigit(peek()));
            } else {
                throw malformed("Expected JSON number.");
            }
            if (peek() == '.') {
                appendNumber(number, take());
                if (!isDigit(peek())) {
                    throw malformed("JSON fraction has no digits.");
                }
                do {
                    appendNumber(number, take());
                } while (isDigit(peek()));
            }
            if (peek() == 'e' || peek() == 'E') {
                appendNumber(number, take());
                if (peek() == '+' || peek() == '-') {
                    appendNumber(number, take());
                }
                if (!isDigit(peek())) {
                    throw malformed("JSON exponent has no digits.");
                }
                do {
                    appendNumber(number, take());
                } while (isDigit(peek()));
            }
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException exception) {
                throw malformed("Invalid JSON number.");
            }
        }

        boolean readBoolean() throws IOException, ChapterParseException {
            token();
            skipWhitespace();
            if (peek() == 't') {
                readLiteral("true");
                return true;
            }
            if (peek() == 'f') {
                readLiteral("false");
                return false;
            }
            throw malformed("Expected JSON boolean.");
        }

        void skipValue() throws IOException, ChapterParseException {
            token();
            skipWhitespace();
            int value = peek();
            if (value == '{') {
                take();
                enterComposite();
                boolean first = true;
                while (nextCompositeValue(first, '}')) {
                    first = false;
                    readName();
                    skipValue();
                }
            } else if (value == '[') {
                take();
                enterComposite();
                boolean first = true;
                while (nextCompositeValue(first, ']')) {
                    first = false;
                    skipValue();
                }
            } else if (value == '"') {
                skipStringBody();
            } else if (value == 't') {
                readLiteral("true");
            } else if (value == 'f') {
                readLiteral("false");
            } else if (value == 'n') {
                readLiteral("null");
            } else if (value == '-' || isDigit(value)) {
                skipNumber();
            } else {
                throw malformed("Unsupported JSON value.");
            }
        }

        void requireEndOfInput() throws IOException, ChapterParseException {
            skipWhitespace();
            if (peek() != -1 || depth != 0) {
                throw malformed("Trailing or incomplete JSON content.");
            }
        }

        private void skipNumber() throws IOException, ChapterParseException {
            int before = tokens;
            tokens--;
            readNumber();
            if (tokens != before) {
                throw new AssertionError("JSON token accounting failed.");
            }
        }

        private String readStringBody(int maxChars)
                throws IOException, ChapterParseException {
            expect('"');
            StringBuilder output = new StringBuilder(Math.min(maxChars, 256));
            while (true) {
                int value = take();
                if (value == -1) {
                    throw malformed("Unterminated JSON string.");
                }
                if (value == '"') {
                    return output.toString();
                }
                int codePoint = readStringCodePoint(value);
                int required = Character.charCount(codePoint);
                if (output.length() > maxChars - required) {
                    throw fieldLimit("JSON string exceeds its field limit.");
                }
                output.appendCodePoint(codePoint);
            }
        }

        private void skipStringBody() throws IOException, ChapterParseException {
            expect('"');
            while (true) {
                int value = take();
                if (value == -1) {
                    throw malformed("Unterminated JSON string.");
                }
                if (value == '"') {
                    return;
                }
                readStringCodePoint(value);
            }
        }

        private int readStringCodePoint(int value)
                throws IOException, ChapterParseException {
            if (value < 0x20) {
                throw malformed("Unescaped control character in JSON string.");
            }
            if (value == '\\') {
                int escaped = take();
                return switch (escaped) {
                    case '"', '\\', '/' -> escaped;
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> readEscapedCodePoint();
                    default -> throw malformed("Invalid JSON string escape.");
                };
            }
            char character = (char) value;
            if (Character.isHighSurrogate(character)) {
                int low = take();
                if (low == -1 || !Character.isLowSurrogate((char) low)) {
                    throw malformed("Unpaired high surrogate in JSON string.");
                }
                return Character.toCodePoint(character, (char) low);
            }
            if (Character.isLowSurrogate(character)) {
                throw malformed("Unpaired low surrogate in JSON string.");
            }
            return value;
        }

        private int readEscapedCodePoint() throws IOException, ChapterParseException {
            char first = readHexQuad();
            if (Character.isHighSurrogate(first)) {
                if (take() != '\\' || take() != 'u') {
                    throw malformed("Unpaired escaped high surrogate.");
                }
                char second = readHexQuad();
                if (!Character.isLowSurrogate(second)) {
                    throw malformed("Invalid escaped surrogate pair.");
                }
                return Character.toCodePoint(first, second);
            }
            if (Character.isLowSurrogate(first)) {
                throw malformed("Unpaired escaped low surrogate.");
            }
            return first;
        }

        private char readHexQuad() throws IOException, ChapterParseException {
            int result = 0;
            for (int index = 0; index < 4; index++) {
                int value = take();
                int digit = Character.digit(value, 16);
                if (digit < 0) {
                    throw malformed("Invalid Unicode escape in JSON string.");
                }
                result = result * 16 + digit;
            }
            return (char) result;
        }

        private void readLiteral(String literal) throws IOException, ChapterParseException {
            for (int index = 0; index < literal.length(); index++) {
                if (take() != literal.charAt(index)) {
                    throw malformed("Invalid JSON literal.");
                }
            }
        }

        private void appendNumber(StringBuilder output, int value)
                throws ChapterParseException {
            if (output.length() >= SecurityLimits.MAX_CHAPTER_JSON_NUMBER_CHARS) {
                throw fieldLimit("JSON number exceeds its lexical limit.");
            }
            output.append((char) value);
        }

        private void enterComposite() throws ChapterParseException {
            depth++;
            if (depth > SecurityLimits.MAX_CHAPTER_JSON_DEPTH) {
                throw new ChapterParseException(
                        ChapterParseException.Reason.DEPTH_LIMIT,
                        "Chapter JSON exceeds its nesting-depth limit.");
            }
        }

        private void token() throws ChapterParseException {
            tokens++;
            if (tokens > SecurityLimits.MAX_CHAPTER_JSON_TOKENS) {
                throw new ChapterParseException(
                        ChapterParseException.Reason.TOKEN_LIMIT,
                        "Chapter JSON exceeds its token limit.");
            }
        }

        private void expect(char expected) throws IOException, ChapterParseException {
            skipWhitespace();
            if (take() != expected) {
                throw malformed("Unexpected JSON token.");
            }
        }

        private void skipWhitespace() throws IOException {
            while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') {
                take();
            }
        }

        private int peek() throws IOException {
            if (lookahead == EMPTY) {
                lookahead = reader.read();
            }
            return lookahead;
        }

        private int take() throws IOException {
            int value = peek();
            lookahead = EMPTY;
            return value;
        }

        private static boolean isDigit(int value) {
            return value >= '0' && value <= '9';
        }

        private static ChapterParseException malformed(String message) {
            return new ChapterParseException(
                    ChapterParseException.Reason.MALFORMED_JSON,
                    message);
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long consumed;

        BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            if (consumed == maximum) {
                int extra = super.read();
                if (extra < 0) {
                    return -1;
                }
                throw new LimitExceededIOException();
            }
            int value = super.read();
            if (value >= 0) {
                consumed++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (consumed == maximum) {
                int extra = super.read();
                if (extra < 0) {
                    return -1;
                }
                throw new LimitExceededIOException();
            }
            int boundedLength = (int) Math.min(length, maximum - consumed);
            int read = super.read(buffer, offset, boundedLength);
            if (read > 0) {
                consumed += read;
            }
            return read;
        }
    }

    private static final class LimitExceededIOException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
