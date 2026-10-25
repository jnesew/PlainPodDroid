package io.github.jnesew.plainpoddroid.core.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class PlainTextSanitizerTest {
    @Test
    public void htmlIsInertAndEmbeddedResourcesAreNeverInterpreted() throws Exception {
        String source = "<p>Hello <b>world</b>.</p>"
                + "<script src=\"https://tracker.example/x.js\">steal()</script>"
                + "<style>body{display:none}</style>"
                + "<img src=\"https://tracker.example/pixel.gif\">Done";

        assertEquals("Hello world . Done", PlainTextSanitizer.sanitize(source, 100));
    }

    @Test
    public void commonAndNumericCdataEntitiesAreDecodedWithoutRecursion() throws Exception {
        String source = "A&nbsp;B &amp; C &#039;D&#039; &#x2026; &unknown;";

        assertEquals(
                "A B & C 'D' … &unknown;",
                PlainTextSanitizer.sanitize(source, 100));
    }

    @Test
    public void normalizedOutputLimitIsFailClosed() {
        assertThrows(
                PlainTextSanitizer.LimitException.class,
                () -> PlainTextSanitizer.sanitize("123456", 5));
    }

    @Test
    public void malformedMarkupCannotTurnRemainingTextIntoActiveContent() throws Exception {
        assertEquals("before", PlainTextSanitizer.sanitize("before <script never closes", 100));
    }
    @Test public void notesPreserveParagraphsLinesAndInlinePunctuation() throws Exception {
        assertEquals("Hello world.\n\nChapters:\n(00:00) – Intro\n(03:00) – Talk",
                PlainTextSanitizer.sanitizeNotes("<p>Hello <b>world</b>.</p><p>Chapters:<br/>(00:00) – Intro<br/>(03:00) – Talk</p>",200));
        assertEquals("One\nTwo\n\nThree",PlainTextSanitizer.sanitizeNotes("One\r\nTwo\r\n\r\n\r\nThree",100));
    }
    @Test public void notesAreInertAndLineBreaksCountTowardLimit() throws Exception {
        assertEquals("Before After",PlainTextSanitizer.sanitizeNotes("Before<script>evil()</script><img src='https://tracker.example/'>After",100));
        assertEquals("A\nB",PlainTextSanitizer.sanitizeNotes("A&#10;B",100));
        assertThrows(PlainTextSanitizer.LimitException.class,()->PlainTextSanitizer.sanitizeNotes("A<br>B",2));
    }
}
