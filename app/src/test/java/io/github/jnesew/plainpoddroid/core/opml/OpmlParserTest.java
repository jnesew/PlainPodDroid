package io.github.jnesew.plainpoddroid.core.opml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class OpmlParserTest {
    private final OpmlParser parser = new OpmlParser();

    @Test
    public void nestedFeedsAreInertAndDeduplicatedAfterCanonicalization() throws Exception {
        OpmlDocument result = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <opml version="2.0"><head><title>Sources</title></head><body>
                  <outline text="Group"><outline text="First" xmlUrl="https://FEED.example/rss?key=a&amp;b=2"/>
                    <outline title="Again" xmlUrl="https://feed.example/rss?key=a&amp;b=2"/>
                  </outline>
                  <outline xmlUrl="https://second.example/feed"/>
                </body></opml>
                """);
        assertEquals(2, result.getFeeds().size());
        assertEquals(1, result.getDuplicateCount());
        assertEquals(0, result.getRejectedCount());
        assertEquals("First", result.getFeeds().get(0).getLabel());
        assertEquals("https://feed.example/rss?key=a&b=2",
                result.getFeeds().get(0).getUrl().toString());
        assertEquals("second.example", result.getFeeds().get(1).getLabel());
    }

    @Test
    public void rejectsUntrustedCandidatesWithoutRejectingSafeOnes() throws Exception {
        OpmlDocument result = parse("""
                <opml><body>
                <outline xmlUrl="http://unsafe.example/feed"/>
                <outline xmlUrl="https://127.0.0.1/feed"/>
                <outline title="Good" xmlUrl="https://safe.example/feed"/>
                </body></opml>
                """);
        assertEquals(1, result.getFeeds().size());
        assertEquals(2, result.getRejectedCount());
    }

    @Test
    public void dtdAndExternalEntitiesNeverReachParsing() {
        assertReason(OpmlParseException.Reason.DTD_FORBIDDEN,
                "<!DOCTYPE opml [<!ENTITY x 'expand'>]><opml><body>&x;</body></opml>");
        assertReason(OpmlParseException.Reason.DTD_FORBIDDEN,
                "<!DOCTYPE opml SYSTEM 'https://example.com/evil.dtd'><opml><body/></opml>");
    }

    @Test
    public void numericReferencesAndPredefinedEntitiesRemainCompatible() throws Exception {
        OpmlDocument result = parse("<opml><body><outline text='A &amp; B &#x26; C' "
                + "xmlUrl='https://example.com/a'/></body></opml>");
        assertEquals("A & B & C", result.getFeeds().get(0).getLabel());
    }

    @Test
    public void invalidUtf8AndOversizedBytesFailClosed() {
        assertEquals(OpmlParseException.Reason.INVALID_ENCODING,
                assertThrows(OpmlParseException.class,
                        () -> parser.parse(new ByteArrayInputStream(new byte[] {
                                '<', 'o', (byte) 0xc3, (byte) 0x28
                        }))).getReason());
        byte[] huge = new byte[2 * 1024 * 1024 + 1];
        assertEquals(OpmlParseException.Reason.INPUT_LIMIT,
                assertThrows(OpmlParseException.class,
                        () -> parser.parse(new ByteArrayInputStream(huge))).getReason());
    }

    @Test
    public void malformedAndWrongRootFailClosed() {
        assertReason(OpmlParseException.Reason.MALFORMED_XML, "<opml><body></opml>");
        assertReason(OpmlParseException.Reason.STRUCTURE, "<rss><body/></rss>");
        assertReason(OpmlParseException.Reason.STRUCTURE, "<opml><head/></opml>");
        assertReason(OpmlParseException.Reason.STRUCTURE,
                "<opml><body/><body/></opml>");
    }

    @Test
    public void nestingAndOutlineCountsAreBounded() {
        StringBuilder deep = new StringBuilder("<opml><body>");
        for (int i = 0; i < 17; i++) deep.append("<outline>");
        for (int i = 0; i < 17; i++) deep.append("</outline>");
        deep.append("</body></opml>");
        assertReason(OpmlParseException.Reason.OUTLINE_LIMIT, deep.toString());

        StringBuilder many = new StringBuilder("<opml><body>");
        for (int i = 0; i < 10_001; i++) many.append("<outline/>");
        many.append("</body></opml>");
        assertReason(OpmlParseException.Reason.OUTLINE_LIMIT, many.toString());
    }

    @Test
    public void candidateAndAttributeLimitsApplyBeforeUrlStorage() {
        StringBuilder many = new StringBuilder("<opml><body>");
        for (int i = 0; i < 5_001; i++) {
            many.append("<outline xmlUrl='https://feed.example/").append(i).append("'/>");
        }
        many.append("</body></opml>");
        assertReason(OpmlParseException.Reason.CANDIDATE_LIMIT, many.toString());

        assertReason(OpmlParseException.Reason.ATTRIBUTE_LIMIT,
                "<opml><body><outline text='" + "x".repeat(4_097)
                        + "'/></body></opml>");
    }

    @Test
    public void namespaceSpoofCannotSupplyAnOpmlBody() {
        assertReason(OpmlParseException.Reason.ATTRIBUTE_LIMIT,
                "<opml xmlns='https://not-opml.example'><body/></opml>");
    }

    private OpmlDocument parse(String xml) throws Exception {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertReason(OpmlParseException.Reason expected, String xml) {
        assertEquals(expected,
                assertThrows(OpmlParseException.class, () -> parse(xml)).getReason());
    }
}
