package io.github.jnesew.plainpoddroid.core.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class FeedParserTest {
    @Test
    public void extensionItemsAndEntriesAreNotOrdinaryEpisodes() throws Exception {
        String rss = "<rss xmlns:vendor=\"https://vendor.example/schema\"><channel><title>Show</title>"
                + "<vendor:item locked=\"true\"><title>Restricted metadata</title>"
                + "<enclosure url=\"https://media.example/locked.mp3\" type=\"audio/mpeg\"/></vendor:item>"
                + "<item><title>Public episode</title></item></channel></rss>";
        FeedDocument document = new FeedParser(limits().episodes(1).build()).parse(
                new ByteArrayInputStream(rss.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, document.getEpisodes().size());
        assertEquals("Public episode", document.getEpisodes().get(0).getTitle());
        String atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\" xmlns:vendor=\"https://vendor.example/schema\">"
                + "<title>Show</title><vendor:entry><title>Extension</title></vendor:entry>"
                + "<entry><title>Public episode</title></entry></feed>";
        assertEquals(1, new FeedParser().parse(new ByteArrayInputStream(atom.getBytes(StandardCharsets.UTF_8)))
                .getEpisodes().size());
    }
    @Test
    public void reasonRoundtableNumericReferencesAreAccepted() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"
                     xmlns:content="http://purl.org/rss/1.0/modules/content/"
                     xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>The Reason Roundtable &#xA9;</title>
                    <itunes:author>Reason</itunes:author>
                    <description><![CDATA[Weekly &nbsp; show &hellip;]]></description>
                    <link>https://reason.com/podcasts/the-reason-roundtable/</link>
                    <itunes:image href="https://reason.com/artwork.jpg" />
                    <category>Politics &#038; Policy</category>
                    <item>
                      <title>Taxes &#038; Trade &#039;Today&#039; &#x1F399;</title>
                      <guid>episode-1</guid>
                      <content:encoded><![CDATA[
                        <p>Mike&#039;s <b>notes</b>&nbsp;&hellip;</p>
                        <script>tracking()</script>
                      ]]></content:encoded>
                      <itunes:duration>01:02:03</itunes:duration>
                      <enclosure
                          url="https://dts.podtrac.com/redirect.mp3/example.com/audio.mp3?a=1&amp;b=2"
                          type="audio/mpeg"
                          length="12345" />
                    </item>
                  </channel>
                </rss>
                """;

        FeedDocument document = parse(new FeedParser(), xml);

        assertEquals(FeedDocument.Format.RSS, document.getFormat());
        assertEquals("The Reason Roundtable ©", document.getTitle());
        assertEquals("Reason", document.getAuthor());
        assertEquals("Weekly show …", document.getNotes());
        assertEquals("Politics & Policy", document.getCategories().get(0));
        assertEquals(1, document.getEpisodes().size());

        FeedDocument.Episode episode = document.getEpisodes().get(0);
        assertEquals("Taxes & Trade 'Today' 🎙", episode.getTitle());
        assertEquals("Mike's notes …", episode.getNotes());
        assertEquals("01:02:03", episode.getDuration());
        assertEquals(1, episode.getEnclosures().size());
        assertEquals(
                "https://dts.podtrac.com/redirect.mp3/example.com/audio.mp3?a=1&b=2",
                episode.getEnclosures().get(0).getUrl());
        assertEquals(12_345L, episode.getEnclosures().get(0).getDeclaredLength());
    }

    @Test
    public void atomFeedUsesNamespaceAwareFieldsAndEnclosures() throws Exception {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Example Atom</title>
                  <author><name>Alice</name></author>
                  <link rel="alternate" href="https://example.com/show" />
                  <category term="Technology" />
                  <entry>
                    <id>tag:example.com,2026:one</id>
                    <title>Episode One</title>
                    <author><name>Bob</name></author>
                    <published>2026-08-27T10:00:00Z</published>
                    <summary type="html">&lt;p&gt;Hello &amp;amp; goodbye&lt;/p&gt;</summary>
                    <link rel="alternate" href="https://example.com/one" />
                    <link rel="enclosure" href="https://cdn.example.com/one.mp3"
                          type="audio/mpeg" length="42" />
                  </entry>
                </feed>
                """;

        FeedDocument document = parse(new FeedParser(), xml);

        assertEquals(FeedDocument.Format.ATOM, document.getFormat());
        assertEquals("Example Atom", document.getTitle());
        assertEquals("Alice", document.getAuthor());
        assertEquals("https://example.com/show", document.getWebsiteUrl());
        assertEquals("Technology", document.getCategories().get(0));
        FeedDocument.Episode episode = document.getEpisodes().get(0);
        assertEquals("tag:example.com,2026:one", episode.getIdentifier());
        assertEquals("Bob", episode.getAuthor());
        assertEquals("Hello & goodbye", episode.getNotes());
        assertEquals("https://example.com/one", episode.getWebsiteUrl());
        assertEquals("2026-08-27T10:00:00Z", episode.getPublished());
        assertEquals("https://cdn.example.com/one.mp3", episode.getEnclosures().get(0).getUrl());
    }

    @Test public void publisherOutlineSurvivesHtmlNotesConversion() throws Exception {
        String xml="<rss><channel><item><title>Long interview</title><description><![CDATA["
                +"<p>About this episode.</p><p>OUTLINE:<br/>(00:00) – Intro<br/>(03:00) – Talk<br/>(1:01:00) – End</p>"
                +"]]></description></item></channel></rss>";
        String notes=parse(new FeedParser(),xml).getEpisodes().get(0).getNotes();
        assertEquals("About this episode.\n\nOUTLINE:\n(00:00) – Intro\n(03:00) – Talk\n(1:01:00) – End",notes);
        assertEquals(3,new io.github.jnesew.plainpoddroid.core.chapter.NotesChapterParser().parse(notes,-1).getChapters().size());
    }

    @Test
    public void podcastChapterReferenceRequiresDirectRecognizedNamespaceTag() throws Exception {
        String xml = """
                <rss xmlns:podcast="https://podcastindex.org/namespace/1.0"
                     xmlns:alternate="https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md"
                     xmlns:wrong="https://example.com/podcast">
                  <channel>
                    <podcast:chapters url="https://ignored.example/feed.json"
                        type="application/json+chapters" />
                    <item>
                      <title>One</title>
                      <podcast:chapters url="https://chapters.example/one.json"
                          type="application/json+chapters" />
                      <wrong:chapters url="https://ignored.example/wrong.json"
                          type="application/json+chapters" />
                      <description>
                        <podcast:chapters url="https://ignored.example/nested.json"
                            type="application/json+chapters" />
                      </description>
                    </item>
                    <item>
                      <title>Two</title>
                      <alternate:chapters url="https://chapters.example/two.json"
                          type="application/json+chapters" />
                    </item>
                  </channel>
                </rss>
                """;

        FeedDocument document = parse(new FeedParser(), xml);

        assertEquals("https://chapters.example/one.json",
                document.getEpisodes().get(0).getChapterUrl());
        assertEquals("application/json+chapters",
                document.getEpisodes().get(0).getChapterMimeType());
        assertEquals("https://chapters.example/two.json",
                document.getEpisodes().get(1).getChapterUrl());
    }

    @Test
    public void duplicateChapterReferencesAreDiscardedWithoutRejectingEpisode() throws Exception {
        String xml = """
                <rss xmlns:p="https://podcastindex.org/namespace/1.0">
                  <channel><item><title>One</title>
                    <p:chapters url="https://chapters.example/one.json"
                        type="application/json+chapters" />
                    <p:chapters url="https://chapters.example/two.json"
                        type="application/json+chapters" />
                  </item></channel>
                </rss>
                """;

        FeedDocument.Episode episode = parse(new FeedParser(), xml).getEpisodes().get(0);

        assertEquals("", episode.getChapterUrl());
        assertEquals("", episode.getChapterMimeType());
    }

    @Test
    public void predefinedEntitiesRemainCompatible() throws Exception {
        FeedDocument document = parse(
                new FeedParser(),
                "<rss><channel><title>&lt;&gt;&amp;&quot;&apos;</title></channel></rss>");

        assertEquals("<>&\"'", document.getTitle());
    }

    @Test
    public void internalEntityDeclarationIsRejectedBeforeExpansion() throws Exception {
        assertFailure(
                new FeedParser(),
                "<!DOCTYPE rss [<!ENTITY custom \"expanded\">]>"
                        + "<rss><channel><title>&custom;</title></channel></rss>",
                FeedParseException.Reason.DTD_FORBIDDEN);
    }

    @Test
    public void billionLaughsDocumentIsRejected() throws Exception {
        String xml = """
                <!DOCTYPE rss [
                  <!ENTITY a "ha">
                  <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">
                  <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;">
                ]>
                <rss><channel><title>&c;</title></channel></rss>
                """;

        assertFailure(new FeedParser(), xml, FeedParseException.Reason.DTD_FORBIDDEN);
    }

    @Test
    public void externalEntityDocumentIsRejectedWithoutResolution() throws Exception {
        String xml = "<!DOCTYPE rss SYSTEM \"https://127.0.0.1/private.dtd\">"
                + "<rss><channel><title>Unsafe</title></channel></rss>";

        assertFailure(new FeedParser(), xml, FeedParseException.Reason.DTD_FORBIDDEN);
    }

    @Test
    public void undeclaredNamedEntityIsRejected() throws Exception {
        FeedParseException exception = assertAnyFailure(
                new FeedParser(),
                "<rss><channel><title>&custom;</title></channel></rss>");

        assertTrue(exception.getReason() == FeedParseException.Reason.MALFORMED_XML
                || exception.getReason() == FeedParseException.Reason.ENTITY_FORBIDDEN);
    }

    @Test
    public void invalidNumericReferenceIsRejected() throws Exception {
        assertFailure(
                new FeedParser(),
                "<rss><channel><title>&#0;</title></channel></rss>",
                FeedParseException.Reason.MALFORMED_XML);
    }

    @Test
    public void byteLimitAcceptsBoundaryAndRejectsNextByte() throws Exception {
        String xml = "<rss><channel><title>x</title></channel></rss>";
        int bytes = xml.getBytes(StandardCharsets.UTF_8).length;

        parse(new FeedParser(limits().expandedBytes(bytes).build()), xml);
        assertFailure(
                new FeedParser(limits().expandedBytes(bytes - 1L).build()),
                xml,
                FeedParseException.Reason.INPUT_LIMIT);
    }

    @Test
    public void depthLimitAcceptsBoundaryAndRejectsNextElement() throws Exception {
        FeedParser parser = new FeedParser(limits().depth(4).build());

        parse(parser, "<rss><channel><x><y/></x></channel></rss>");
        assertFailure(
                parser,
                "<rss><channel><x><y><z/></y></x></channel></rss>",
                FeedParseException.Reason.DEPTH_LIMIT);
    }

    @Test
    public void attributeCountAndValueLimitsAreEnforced() throws Exception {
        FeedParser countParser = new FeedParser(limits().attributes(2).build());
        parse(countParser, "<rss><channel><x a=\"1\" b=\"2\"/></channel></rss>");
        assertFailure(
                countParser,
                "<rss><channel><x a=\"1\" b=\"2\" c=\"3\"/></channel></rss>",
                FeedParseException.Reason.ATTRIBUTE_LIMIT);

        FeedParser valueParser = new FeedParser(limits().attributeChars(3).build());
        parse(valueParser, "<rss><channel><x a=\"123\"/></channel></rss>");
        assertFailure(
                valueParser,
                "<rss><channel><x a=\"1234\"/></channel></rss>",
                FeedParseException.Reason.ATTRIBUTE_VALUE_LIMIT);
    }

    @Test
    public void episodeLimitRejectsLimitPlusOneWithoutReturningPartialFeed() throws Exception {
        FeedParser parser = new FeedParser(limits().episodes(2).build());

        FeedDocument accepted = parse(
                parser,
                "<rss><channel><item/><item/></channel></rss>");
        assertEquals(2, accepted.getEpisodes().size());
        assertFailure(
                parser,
                "<rss><channel><item/><item/><item/></channel></rss>",
                FeedParseException.Reason.EPISODE_LIMIT);
    }

    @Test
    public void aggregateAndPerFieldTextLimitsAreEnforced() throws Exception {
        FeedParser aggregateParser = new FeedParser(
                limits().aggregateTextChars(5).feedTitleChars(20).build());
        parse(aggregateParser, "<rss><channel><title>12345</title></channel></rss>");
        assertFailure(
                aggregateParser,
                "<rss><channel><title>123456</title></channel></rss>",
                FeedParseException.Reason.TEXT_LIMIT);

        FeedParser titleParser = new FeedParser(
                limits().aggregateTextChars(20).feedTitleChars(5).build());
        parse(titleParser, "<rss><channel><title>12345</title></channel></rss>");
        assertFailure(
                titleParser,
                "<rss><channel><title>123456</title></channel></rss>",
                FeedParseException.Reason.FIELD_LIMIT);
    }

    @Test
    public void descriptionSourceAndPlainTextLimitsAreIndependent() throws Exception {
        FeedParser rawParser = new FeedParser(
                limits().descriptionSourceChars(5).plainNotesChars(20).build());
        parse(rawParser, "<rss><channel><description>12345</description></channel></rss>");
        assertFailure(
                rawParser,
                "<rss><channel><description>123456</description></channel></rss>",
                FeedParseException.Reason.FIELD_LIMIT);

        FeedParser plainParser = new FeedParser(
                limits().descriptionSourceChars(20).plainNotesChars(5).build());
        parse(plainParser, "<rss><channel><description>12345</description></channel></rss>");
        assertFailure(
                plainParser,
                "<rss><channel><description>123456</description></channel></rss>",
                FeedParseException.Reason.FIELD_LIMIT);
    }

    @Test
    public void categoriesRejectLimitPlusOneAndEnclosuresStopAtConsiderationLimit()
            throws Exception {
        FeedParser parser = new FeedParser(limits().categories(2).enclosures(2).build());
        FeedDocument accepted = parse(
                parser,
                "<rss><channel><item>"
                        + "<category>a</category><category>b</category>"
                        + "<enclosure url=\"https://example.com/1.mp3\"/>"
                        + "<enclosure url=\"https://example.com/2.mp3\"/>"
                        + "<enclosure url=\"https://example.com/3.mp3\"/>"
                        + "</item></channel></rss>");
        assertEquals(2, accepted.getEpisodes().get(0).getCategories().size());
        assertEquals(2, accepted.getEpisodes().get(0).getEnclosures().size());
        assertFailure(
                parser,
                "<rss><channel><item>"
                        + "<category>a</category><category>b</category><category>c</category>"
                        + "</item></channel></rss>",
                FeedParseException.Reason.FIELD_LIMIT);
    }

    @Test
    public void parserDoesNotTakeOwnershipOfCallerStream() throws Exception {
        CloseTrackingInputStream input = new CloseTrackingInputStream(
                "<rss><channel/></rss>".getBytes(StandardCharsets.UTF_8));

        new FeedParser().parse(input);

        assertFalse(input.closed);
    }

    @Test
    public void unsupportedRootFailsClosed() throws Exception {
        assertFailure(
                new FeedParser(),
                "<opml version=\"2.0\"/>",
                FeedParseException.Reason.UNSUPPORTED_DOCUMENT);
    }

    private static FeedDocument parse(FeedParser parser, String xml) throws Exception {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertFailure(
            FeedParser parser,
            String xml,
            FeedParseException.Reason expectedReason) throws Exception {
        FeedParseException exception = assertAnyFailure(parser, xml);
        assertEquals(expectedReason, exception.getReason());
    }

    private static FeedParseException assertAnyFailure(FeedParser parser, String xml)
            throws Exception {
        try {
            parse(parser, xml);
            fail("Expected feed parsing to fail");
            throw new AssertionError("unreachable");
        } catch (FeedParseException exception) {
            return exception;
        }
    }

    private static LimitsBuilder limits() {
        return new LimitsBuilder();
    }

    private static final class LimitsBuilder {
        long expandedBytes = 16_384;
        int depth = 16;
        int attributes = 8;
        int attributeChars = 256;
        int episodes = 8;
        int aggregateTextChars = 8_192;
        int feedTitleChars = 256;
        int episodeTitleChars = 256;
        int authorChars = 128;
        int identifierChars = 256;
        int descriptionSourceChars = 1_024;
        int plainNotesChars = 512;
        int categories = 8;
        int categoryChars = 64;
        int enclosures = 4;

        LimitsBuilder expandedBytes(long value) {
            expandedBytes = value;
            return this;
        }

        LimitsBuilder depth(int value) {
            depth = value;
            return this;
        }

        LimitsBuilder attributes(int value) {
            attributes = value;
            return this;
        }

        LimitsBuilder attributeChars(int value) {
            attributeChars = value;
            return this;
        }

        LimitsBuilder episodes(int value) {
            episodes = value;
            return this;
        }

        LimitsBuilder aggregateTextChars(int value) {
            aggregateTextChars = value;
            return this;
        }

        LimitsBuilder feedTitleChars(int value) {
            feedTitleChars = value;
            return this;
        }

        LimitsBuilder descriptionSourceChars(int value) {
            descriptionSourceChars = value;
            return this;
        }

        LimitsBuilder plainNotesChars(int value) {
            plainNotesChars = value;
            return this;
        }

        LimitsBuilder categories(int value) {
            categories = value;
            return this;
        }

        LimitsBuilder enclosures(int value) {
            enclosures = value;
            return this;
        }

        XmlFeedLimits build() {
            return new XmlFeedLimits(
                    expandedBytes,
                    depth,
                    attributes,
                    attributeChars,
                    episodes,
                    aggregateTextChars,
                    feedTitleChars,
                    episodeTitleChars,
                    authorChars,
                    identifierChars,
                    descriptionSourceChars,
                    plainNotesChars,
                    categories,
                    categoryChars,
                    enclosures);
        }
    }

    private static final class CloseTrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        boolean closed;

        CloseTrackingInputStream(byte[] data) {
            delegate = new ByteArrayInputStream(data);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }
}
