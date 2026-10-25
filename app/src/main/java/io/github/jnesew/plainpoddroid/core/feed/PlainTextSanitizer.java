package io.github.jnesew.plainpoddroid.core.feed;

import java.text.Normalizer;
import java.util.Locale;

final class PlainTextSanitizer {
    private PlainTextSanitizer() {
        throw new AssertionError("No instances");
    }

    static String sanitize(String source, int maxChars) throws LimitException {
        return sanitize(source, maxChars, false);
    }

    static String sanitizeNotes(String source, int maxChars) throws LimitException {
        return sanitize(source, maxChars, true);
    }

    private static String sanitize(String source, int maxChars, boolean keepLines) throws LimitException {
        if (source == null || source.isEmpty()) {
            return "";
        }

        StringBuilder output = new StringBuilder(Math.min(source.length(), maxChars));
        int separator = 0;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '<') {
                if (source.startsWith("<!--", index)) {
                    int commentEnd = source.indexOf("-->", index + 4);
                    if (commentEnd < 0) {
                        break;
                    }
                    index = commentEnd + 3;
                    separator = Math.max(separator, output.length() != 0 ? 1 : 0);
                    continue;
                }

                Tag tag = parseTag(source, index);
                if (tag == null) {
                    break;
                }
                index = tag.end + 1;
                int gap = keepLines ? tagGap(tag.name) : 1;
                separator = Math.max(separator, output.length() != 0 ? gap : 0);
                if (!tag.closing && (tag.name.equals("script") || tag.name.equals("style"))) {
                    int closeStart = indexOfIgnoreCase(source, "</" + tag.name, index);
                    if (closeStart < 0) {
                        break;
                    }
                    Tag closeTag = parseTag(source, closeStart);
                    if (closeTag == null) {
                        break;
                    }
                    index = closeTag.end + 1;
                }
                continue;
            }

            if (current == '&') {
                DecodedEntity entity = decodeEntity(source, index);
                if (entity != null) {
                    if (entity.whitespace) {
                        int gap = keepLines && (entity.codePoint == '\n' || entity.codePoint == '\r') ? 2 : 1;
                        separator = Math.max(separator, output.length() != 0 ? gap : 0);
                    } else {
                        appendSeparator(output, separator, maxChars); separator = 0;
                        appendCodePoint(output, entity.codePoint, maxChars);
                    }
                    index = entity.end + 1;
                    continue;
                }
            }

            int codePoint = source.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                if (keepLines && (codePoint == '\n' || codePoint == '\r')) {
                    boolean crlf = codePoint == '\n' && index >= 2 && source.charAt(index - 2) == '\r';
                    if (!crlf && output.length() != 0) separator = separator >= 2 ? 3 : 2;
                } else separator = Math.max(separator, output.length() != 0 ? 1 : 0);
                continue;
            }
            appendSeparator(output, separator, maxChars); separator = 0;
            appendCodePoint(output, codePoint, maxChars);
        }

        String normalized = Normalizer.normalize(output, Normalizer.Form.NFC);
        if (normalized.length() > maxChars) {
            throw new LimitException();
        }
        return normalized;
    }

    private static int tagGap(String name) {
        return switch (name) {
            case "br", "li", "tr" -> 2;
            case "p", "div", "section", "article", "blockquote", "ul", "ol",
                    "h1", "h2", "h3", "h4", "h5", "h6", "pre" -> 3;
            case "a", "b", "i", "em", "strong", "span", "code", "small", "s", "u", "sub", "sup" -> 0;
            default -> 1;
        };
    }

    private static void appendSeparator(StringBuilder output, int separator, int maxChars) throws LimitException {
        if (output.length() == 0) return;
        if (separator == 1) appendCodePoint(output, ' ', maxChars);
        else if (separator >= 2) {
            appendCodePoint(output, '\n', maxChars);
            if (separator >= 3) appendCodePoint(output, '\n', maxChars);
        }
    }

    private static void appendCodePoint(
            StringBuilder output,
            int codePoint,
            int maxChars) throws LimitException {
        if (!Character.isValidCodePoint(codePoint)
                || codePoint == 0
                || codePoint >= Character.MIN_SURROGATE
                && codePoint <= Character.MAX_SURROGATE) {
            codePoint = 0xfffd;
        }
        int required = Character.charCount(codePoint);
        if (output.length() > maxChars - required) {
            throw new LimitException();
        }
        output.appendCodePoint(codePoint);
    }

    private static Tag parseTag(String source, int start) {
        int index = start + 1;
        boolean closing = false;
        if (index < source.length() && source.charAt(index) == '/') {
            closing = true;
            index++;
        }
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
        int nameStart = index;
        while (index < source.length()) {
            char value = source.charAt(index);
            if (!Character.isLetterOrDigit(value) && value != ':' && value != '-' && value != '_') {
                break;
            }
            index++;
        }
        if (index == nameStart) {
            int declarationEnd = findTagEnd(source, start + 1);
            return declarationEnd < 0 ? null : new Tag("", closing, declarationEnd);
        }
        int end = findTagEnd(source, index);
        if (end < 0) {
            return null;
        }
        String name = source.substring(nameStart, index).toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0 && colon + 1 < name.length()) {
            name = name.substring(colon + 1);
        }
        return new Tag(name, closing, end);
    }

    private static int findTagEnd(String source, int start) {
        char quote = 0;
        for (int index = start; index < source.length(); index++) {
            char value = source.charAt(index);
            if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                }
            } else if (value == '\'' || value == '"') {
                quote = value;
            } else if (value == '>') {
                return index;
            }
        }
        return -1;
    }

    private static DecodedEntity decodeEntity(String source, int start) {
        int end = source.indexOf(';', start + 1);
        if (end < 0 || end - start > 16) {
            return null;
        }
        String name = source.substring(start + 1, end);
        if (name.equals("nbsp")) {
            return DecodedEntity.whitespace(end);
        }
        Integer named = switch (name) {
            case "amp" -> (int) '&';
            case "apos" -> (int) '\'';
            case "gt" -> (int) '>';
            case "hellip" -> 0x2026;
            case "ldquo" -> 0x201c;
            case "lsquo" -> 0x2018;
            case "lt" -> (int) '<';
            case "mdash" -> 0x2014;
            case "ndash" -> 0x2013;
            case "quot" -> (int) '"';
            case "rdquo" -> 0x201d;
            case "rsquo" -> 0x2019;
            default -> null;
        };
        if (named != null) {
            return DecodedEntity.codePoint(named, end);
        }
        if (!name.startsWith("#")) {
            return null;
        }

        int radix = 10;
        int digitsStart = 1;
        if (name.length() > 2 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')) {
            radix = 16;
            digitsStart = 2;
        }
        if (digitsStart >= name.length()) {
            return null;
        }
        try {
            int codePoint = Integer.parseInt(name.substring(digitsStart), radix);
            return DecodedEntity.codePoint(codePoint, end);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int indexOfIgnoreCase(String source, String target, int fromIndex) {
        int lastStart = source.length() - target.length();
        for (int index = fromIndex; index <= lastStart; index++) {
            if (source.regionMatches(true, index, target, 0, target.length())) {
                return index;
            }
        }
        return -1;
    }

    static final class LimitException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class Tag {
        final String name;
        final boolean closing;
        final int end;

        Tag(String name, boolean closing, int end) {
            this.name = name;
            this.closing = closing;
            this.end = end;
        }
    }

    private static final class DecodedEntity {
        final int codePoint;
        final int end;
        final boolean whitespace;

        private DecodedEntity(int codePoint, int end, boolean whitespace) {
            this.codePoint = codePoint;
            this.end = end;
            this.whitespace = whitespace;
        }

        static DecodedEntity codePoint(int codePoint, int end) {
            return new DecodedEntity(codePoint, end,
                    Character.isWhitespace(codePoint) || Character.isISOControl(codePoint));
        }

        static DecodedEntity whitespace(int end) {
            return new DecodedEntity(0, end, true);
        }
    }
}
