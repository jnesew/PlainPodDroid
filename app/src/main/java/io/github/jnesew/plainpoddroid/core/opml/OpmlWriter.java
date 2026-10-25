package io.github.jnesew.plainpoddroid.core.opml;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Produces bounded UTF-8 bytes before the SAF destination is opened. */
public final class OpmlWriter {
    public byte[] write(List<OpmlDocument.Feed> feeds) throws IOException {
        Objects.requireNonNull(feeds, "feeds");
        if (feeds.size() > SecurityLimits.MAX_SUBSCRIPTIONS) {
            throw new IOException("OPML export exceeds the subscription limit.");
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<opml version=\"2.0\"><head><title>PlainPodDroid</title></head><body>\n");
        for (OpmlDocument.Feed feed : feeds) {
            Objects.requireNonNull(feed, "feed");
            xml.append("<outline type=\"rss\" text=\"");
            appendAttribute(xml, feed.getLabel());
            xml.append("\" xmlUrl=\"");
            appendAttribute(xml, feed.getUrl().toString());
            xml.append("\"/>\n");
            if (xml.length() > SecurityLimits.MAX_OPML_BYTES) {
                throw new IOException("OPML export exceeds the byte limit.");
            }
        }
        byte[] bytes = xml.append("</body></opml>\n").toString()
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SecurityLimits.MAX_OPML_BYTES) {
            throw new IOException("OPML export exceeds the byte limit.");
        }
        return bytes;
    }

    private static void appendAttribute(StringBuilder xml, String value) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!validXmlCharacter(codePoint)) {
                xml.append(' ');
            } else if (codePoint == '&') {
                xml.append("&amp;");
            } else if (codePoint == '<') {
                xml.append("&lt;");
            } else if (codePoint == '>') {
                xml.append("&gt;");
            } else if (codePoint == '"') {
                xml.append("&quot;");
            } else if (codePoint == '\'') {
                xml.append("&apos;");
            } else {
                xml.appendCodePoint(codePoint);
            }
        }
    }

    private static boolean validXmlCharacter(int cp) {
        return cp == 0x9 || cp == 0xa || cp == 0xd
                || cp >= 0x20 && cp <= 0xd7ff
                || cp >= 0xe000 && cp <= 0xfffd
                || cp >= 0x10000 && cp <= 0x10ffff;
    }
}
