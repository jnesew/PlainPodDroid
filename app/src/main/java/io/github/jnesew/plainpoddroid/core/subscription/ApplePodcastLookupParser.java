package io.github.jnesew.plainpoddroid.core.subscription;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON reader with byte, depth, token and individual-string ceilings. */
final class ApplePodcastLookupParser {
    private final String text;
    private int position;
    private int tokens;

    private ApplePodcastLookupParser(String text) { this.text = text; }

    static CanonicalHttpsUrl parse(InputStream input, String requestedId) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length,
                SecurityLimits.MAX_APPLE_LOOKUP_BYTES + 1 - bytes.size()))) != -1) {
            bytes.write(buffer, 0, count);
            if (bytes.size() > SecurityLimits.MAX_APPLE_LOOKUP_BYTES) throw invalid();
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException exception) { throw invalid(); }
        ApplePodcastLookupParser parser = new ApplePodcastLookupParser(text);
        Object value = parser.value(0);
        parser.space();
        if (parser.position != text.length() || !(value instanceof Map<?, ?> root)) throw invalid();
        if (!(root.get("resultCount") instanceof NumberText resultCount)
                || !(root.get("results") instanceof List<?> results)) throw invalid();
        if (resultCount.text.equals("0") && results.isEmpty()) throw noFeed();
        if (!resultCount.text.equals("1") || results.size() != 1
                || !(results.get(0) instanceof Map<?, ?> result)) throw invalid();
        if (!"podcast".equals(result.get("kind"))
                || !(result.get("collectionId") instanceof NumberText id)
                || !id.text.equals(requestedId)) throw invalid();
        if (result.containsKey("trackId") && (!(result.get("trackId") instanceof NumberText trackId)
                || !trackId.text.equals(requestedId))) throw invalid();
        Object feed = result.get("feedUrl");
        if (feed == null || "".equals(feed)) throw noFeed();
        if (!(feed instanceof String url)) throw invalid();
        try { return CanonicalHttpsUrl.parse(url); }
        catch (NetworkPolicyException exception) { throw invalid(); }
    }

    private Object value(int depth) throws ApplePodcastLookupException {
        if (++tokens > SecurityLimits.MAX_APPLE_LOOKUP_JSON_TOKENS
                || depth > SecurityLimits.MAX_APPLE_LOOKUP_JSON_DEPTH) throw invalid();
        space();
        char first = peek();
        if (first == '{') {
            position++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (consume('}')) return map;
            do {
                if (++tokens > SecurityLimits.MAX_APPLE_LOOKUP_JSON_TOKENS) throw invalid();
                String key = string();
                if (key.length() > 128 || map.containsKey(key)) throw invalid();
                require(':');
                map.put(key, value(depth + 1));
                space();
                if (consume('}')) return map;
                require(',');
            } while (true);
        }
        if (first == '[') {
            position++;
            List<Object> list = new ArrayList<>();
            space();
            if (consume(']')) return list;
            do {
                list.add(value(depth + 1));
                space();
                if (consume(']')) return list;
                require(',');
            } while (true);
        }
        if (first == '"') return string();
        if (first == 't') { literal("true"); return Boolean.TRUE; }
        if (first == 'f') { literal("false"); return Boolean.FALSE; }
        if (first == 'n') { literal("null"); return null; }
        int start = position;
        while (position < text.length() && "0123456789.eE+-".indexOf(text.charAt(position)) >= 0) {
            if (++position - start > 64) throw invalid();
        }
        String number = text.substring(start, position);
        if (!number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw invalid();
        return new NumberText(number);
    }

    private String string() throws ApplePodcastLookupException {
        space();
        require('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= text.length()) throw invalid();
            char ch = text.charAt(position++);
            if (ch == '"') {
                for (int i = 0; i < out.length(); i++) {
                    char c = out.charAt(i);
                    if (Character.isHighSurrogate(c)) {
                        if (++i >= out.length() || !Character.isLowSurrogate(out.charAt(i))) throw invalid();
                    } else if (Character.isLowSurrogate(c)) throw invalid();
                }
                return out.toString();
            }
            if (ch < 0x20) throw invalid();
            if (ch == '\\') {
                if (position >= text.length()) throw invalid();
                ch = text.charAt(position++);
                switch (ch) {
                    case '"', '\\', '/' -> out.append(ch);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) throw invalid();
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(position++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                    : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw invalid();
                            code = code * 16 + digit;
                        }
                        out.append((char) code);
                    }
                    default -> throw invalid();
                }
            } else out.append(ch);
            if (out.length() > SecurityLimits.MAX_URL_CHARS) throw invalid();
        }
    }

    private char peek() throws ApplePodcastLookupException {
        if (position >= text.length()) throw invalid();
        return text.charAt(position);
    }
    private boolean consume(char ch) {
        if (position < text.length() && text.charAt(position) == ch) { position++; return true; }
        return false;
    }
    private void require(char ch) throws ApplePodcastLookupException {
        space();
        if (!consume(ch)) throw invalid();
    }
    private void literal(String expected) throws ApplePodcastLookupException {
        if (!text.startsWith(expected, position)) throw invalid();
        position += expected.length();
    }
    private void space() {
        while (position < text.length() && " \t\n\r".indexOf(text.charAt(position)) >= 0) position++;
    }
    private static ApplePodcastLookupException invalid() {
        return new ApplePodcastLookupException(ApplePodcastLookupException.Reason.INVALID_RESPONSE);
    }
    private static ApplePodcastLookupException noFeed() {
        return new ApplePodcastLookupException(ApplePodcastLookupException.Reason.NO_PUBLIC_FEED);
    }
    private static final class NumberText {
        private final String text;
        NumberText(String text) { this.text = text; }
    }
}
