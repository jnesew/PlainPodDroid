package io.github.jnesew.plainpoddroid;

import android.test.InstrumentationTestCase;
import io.github.jnesew.plainpoddroid.core.feed.FeedParser;
import io.github.jnesew.plainpoddroid.core.feed.FeedParseException;
import io.github.jnesew.plainpoddroid.core.opml.OpmlParser;
import io.github.jnesew.plainpoddroid.core.opml.OpmlParseException;
import io.github.jnesew.plainpoddroid.core.chapter.PodcastChapterParser;
import io.github.jnesew.plainpoddroid.core.chapter.ChapterParseException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Exercise the actual Android XML/UTF-8 boundary with inert synthetic input. */
@SuppressWarnings("deprecation")
public final class SecurityIngressPlatformTest extends InstrumentationTestCase {
    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    public void testFeedXmlRejectsDtdAndTrailingContentButAcceptsNumericReferences() throws Exception {
        FeedParser parser = new FeedParser();
        String xml = "<rss><channel><title>A &#38; B</title></channel></rss>";
        assertEquals("A & B", parser.parse(bytes(xml)).getTitle());
        for (String dtd : new String[] {
                "<!DOCTYPE rss [<!ENTITY secret 'expanded'>]>",
                "<!DOCTYPE rss SYSTEM 'https://never-contact.example/forbidden.dtd'>"}) {
            try { parser.parse(bytes(dtd + xml)); fail("DTD accepted"); }
            catch (FeedParseException expected) {
                assertEquals(FeedParseException.Reason.DTD_FORBIDDEN, expected.getReason());
            }
        }
        try { parser.parse(bytes(xml + "<extra/>")); fail("Trailing document accepted"); }
        catch (FeedParseException expected) {
            assertEquals(FeedParseException.Reason.MALFORMED_XML, expected.getReason());
        }
    }

    public void testOpmlRejectsDtdAndUnsafeCandidatesWithoutFetching() throws Exception {
        OpmlParser parser = new OpmlParser();
        String xml = "<opml><body><outline text='A &#38; B' xmlUrl='https://feed.example/rss'/>"
                + "<outline xmlUrl='https://127.0.0.1/rss'/><outline xmlUrl='http://feed.example/rss'/>"
                + "</body></opml>";
        var document = parser.parse(bytes(xml));
        assertEquals(1, document.getFeeds().size());
        assertEquals(2, document.getRejectedCount());
        assertEquals("A & B", document.getFeeds().get(0).getLabel());
        try { parser.parse(bytes("<!DOCTYPE opml SYSTEM 'https://never-contact.example/forbidden.dtd'>" + xml));
            fail("OPML DTD accepted");
        } catch (OpmlParseException expected) {
            assertEquals(OpmlParseException.Reason.DTD_FORBIDDEN, expected.getReason());
        }
    }

    public void testChapterJsonRejectsTrailingInputAndExcessDepth() throws Exception {
        PodcastChapterParser parser = new PodcastChapterParser();
        String json = "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":0,\"title\":\"Intro\"}]}";
        assertEquals(1, parser.parse(bytes(json)).getChapters().size());
        try { parser.parse(bytes(json + "{}")); fail("Trailing JSON accepted"); }
        catch (ChapterParseException expected) { /* Whole-document rejection. */ }
        String deep = "{\"version\":\"1.2\",\"chapters\":[],\"ignored\":"
                + "[".repeat(17) + "0" + "]".repeat(17) + "}";
        try { parser.parse(bytes(deep)); fail("Excess JSON depth accepted"); }
        catch (ChapterParseException expected) { /* Unknown fields retain depth limits. */ }
    }

    public void testExtremeChapterTimeExponentsStayBoundedOnAndroid() throws Exception {
        PodcastChapterParser parser = new PodcastChapterParser();
        for (String time : new String[] {"1e-100000000", "1e-2147483647", "0e2147483647"}) {
            String json = "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":" + time + "}]}";
            assertEquals(0L, parser.parse(bytes(json)).getChapters().get(0).getStartTimeMillis());
        }
        String boundary = "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":0.0005}]}";
        assertEquals(1L, parser.parse(bytes(boundary)).getChapters().get(0).getStartTimeMillis());
    }
}
