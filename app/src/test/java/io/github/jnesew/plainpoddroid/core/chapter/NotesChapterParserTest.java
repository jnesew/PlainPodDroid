package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NotesChapterParserTest {
    private final NotesChapterParser parser = new NotesChapterParser();
    @Test public void outlineLinesBecomeInertChaptersWithEndTimes() {
        PodcastChapterDocument doc = parser.parse("About the show\n\nOUTLINE:\n(00:00) – Intro\n(03:00) – Talk\n(1:02:03) – Finish", 4_000_000);
        assertNotNull(doc); assertEquals(3, doc.getChapters().size());
        assertEquals(180_000, doc.getChapters().get(0).getEndTimeMillis());
        PodcastChapter last = doc.getChapters().get(2);
        assertEquals(3_723_000, last.getStartTimeMillis()); assertEquals(4_000_000,last.getEndTimeMillis());
        assertNull(last.getWebsiteUrl()); assertNull(last.getImageUrl()); assertTrue(last.isVisible());
    }
    @Test public void olderFlattenedOutlineHasAnExplicitHeading() {
        assertNotNull(parser.parse("Links here. OUTLINE: (00:00) – Intro (02:10) – Main (1:05:00) – End", -1));
        assertNull(parser.parse("(00:00) – Intro (02:10) – Main (1:05:00) – End", -1));
        assertNotNull(parser.parse("00:00 Intro\n02:10 Main\n1:05:00 End", -1));
    }
    @Test public void malformedDuplicateDescendingAndOutOfDurationTimesAreRejected() {
        for (String notes : new String[] {
                "00:00 Intro\n01:00 Main\n00:59 End",
                "00:00 Intro\n01:00 Main\n01:00 End",
                "00:00 Intro\n01:00 Main\n02:60 End",
                "00:00 Intro\n01:00 Main\n02:00 End\n03:999 Broken",
                "00:00 Intro\n01:00 Main\n02:00 End" }) {
            assertNull(notes,parser.parse(notes,120_000));
        }
        assertNull(parser.parse("OUTLINE: (00:00) – A (00:99) – B (02:00) – C",-1));
        assertNull(parser.parse("OUTLINE: (00:00) – A (01:00) – B (02:00) – C (3:0) – Broken",-1));
    }
    @Test public void ambiguousOrIncidentalTimestampTextStaysAsNotes() {
        assertNull(parser.parse("Discussed at 00:10.\n00:20 Reference\n00:30 Reference",-1));
        assertNull(parser.parse("00:00 A\n01:00 B\n02:00 C\nOther list\n00:00 D\n01:00 E\n02:00 F",-1));
        assertNull(parser.parse("OUTLINE: (00:00) – A (01:00) – B (02:00) – "+"x".repeat(257),-1));
    }
    @Test public void textTitleCountAndMediaLimitsAreEnforced() {
        assertNull(parser.parse("x".repeat(SecurityLimits.MAX_PLAIN_NOTES_CHARS+1),-1));
        StringBuilder many=new StringBuilder();
        for(int i=0;i<=SecurityLimits.MAX_CHAPTERS;i++)many.append(String.format("%d:%02d Title\n",i/60,i%60));
        assertNull(parser.parse(many.toString(),-1));
        assertNull(parser.parse("00:00 A\n01:00 B\n02:00 C",SecurityLimits.MAX_MEDIA_DURATION_MILLIS+1));
    }
}
