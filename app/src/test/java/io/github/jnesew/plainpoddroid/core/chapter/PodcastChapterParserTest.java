package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PodcastChapterParserTest {
    private final PodcastChapterParser parser = new PodcastChapterParser();

    @Test
    public void parsesRequiredAndOptionalVersionOneFields() throws Exception {
        PodcastChapterDocument document = parse("""
                {
                  "version": "1.2.0",
                  "chapters": [
                    {"startTime": 0, "title": "Intro"},
                    {
                      "startTime": 94.1254,
                      "endTime": 120.5,
                      "title": "  Cafe\\u0301\\nsegment  ",
                      "img": "https://example.com/art.jpg",
                      "url": "https://example.com/topic",
                      "toc": false,
                      "location": {"name": "Ignored", "geo": "geo:1,2"}
                    }
                  ],
                  "description": "Ignored without being retained"
                }
                """);

        assertEquals("1.2.0", document.getVersion());
        assertEquals(2, document.getChapters().size());
        PodcastChapter first = document.getChapters().get(0);
        assertEquals(0L, first.getStartTimeMillis());
        assertEquals(PodcastChapter.UNKNOWN_END_TIME_MILLIS, first.getEndTimeMillis());
        assertEquals("Intro", first.getTitle());
        assertTrue(first.isVisible());
        assertNull(first.getImageUrl());
        assertNull(first.getWebsiteUrl());

        PodcastChapter second = document.getChapters().get(1);
        assertEquals(94_125L, second.getStartTimeMillis());
        assertEquals(120_500L, second.getEndTimeMillis());
        assertEquals("Café segment", second.getTitle());
        assertFalse(second.isVisible());
        assertEquals("https://example.com/art.jpg", second.getImageUrl().toString());
        assertEquals("https://example.com/topic", second.getWebsiteUrl().toString());
    }

    @Test
    public void acceptsEmptyAndEqualAscendingChapterPositions() throws Exception {
        assertTrue(parse("{\"version\":\"1.0\",\"chapters\":[]}")
                .getChapters().isEmpty());
        assertEquals(2, parse("""
                {"version":"1.2","chapters":[
                  {"startTime":1},{"startTime":1}
                ]}
                """).getChapters().size());
    }

    @Test
    public void roundsFiniteFractionalSecondsToNearestMillisecond() throws Exception {
        PodcastChapterDocument document = parse("""
                {"version":"1.2","chapters":[
                  {"startTime":1.2344,"endTime":1.2345},
                  {"startTime":2e0}
                ]}
                """);

        assertEquals(1_234L, document.getChapters().get(0).getStartTimeMillis());
        assertEquals(1_235L, document.getChapters().get(0).getEndTimeMillis());
        assertEquals(2_000L, document.getChapters().get(1).getStartTimeMillis());
    }

    @Test(timeout = 5_000L)
    public void handlesExtremeTinyExponentsWithoutUnboundedDecimalScaling() throws Exception {
        for (String time : new String[] {"1e-100000000", "1e-2147483647", "0e2147483647"}) {
            var chapter = parse("{\"version\":\"1.2\",\"chapters\":[{\"startTime\":"
                    + time + ",\"endTime\":" + time + "}]}").getChapters().get(0);
            assertEquals(0L, chapter.getStartTimeMillis());
            assertEquals(0L, chapter.getEndTimeMillis());
        }
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":-1e-100000000}]}");
    }

    @Test
    public void preservesHalfMillisecondRoundingAtTheTinyTimeBoundary() throws Exception {
        for (String time : new String[] {"0.000499999", "0.0005", "0.001499999", "0.0015"}) {
            long expected = switch (time) {
                case "0.000499999" -> 0L;
                case "0.0005", "0.001499999" -> 1L;
                default -> 2L;
            };
            assertEquals(expected, parse("{\"version\":\"1.2\",\"chapters\":[{\"startTime\":"
                    + time + "}]}").getChapters().get(0).getStartTimeMillis());
        }
    }

    @Test
    public void rejectsMissingDuplicateOrWrongTypedRequiredFields() {
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                "{\"chapters\":[]}");
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                "{\"version\":\"1.2\"}");
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                "{\"version\":\"1.2\",\"version\":\"1.2\",\"chapters\":[]}");
        assertReason(ChapterParseException.Reason.MALFORMED_JSON,
                "{\"version\":1.2,\"chapters\":[]}");
        assertReason(ChapterParseException.Reason.MALFORMED_JSON,
                "{\"version\":\"1.2\",\"chapters\":{}}");
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                "{\"version\":\"1.2\",\"chapters\":[{}]}");
    }

    @Test
    public void rejectsUnsupportedVersions() {
        assertReason(ChapterParseException.Reason.UNSUPPORTED_VERSION,
                "{\"version\":\"2.0\",\"chapters\":[]}");
        assertReason(ChapterParseException.Reason.UNSUPPORTED_VERSION,
                "{\"version\":\"1\",\"chapters\":[]}");
        assertReason(ChapterParseException.Reason.UNSUPPORTED_VERSION,
                "{\"version\":\"latest\",\"chapters\":[]}");
    }

    @Test
    public void rejectsNonAscendingOrInconsistentTimes() {
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA, """
                {"version":"1.2","chapters":[
                  {"startTime":2},{"startTime":1}
                ]}
                """);
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                "{\"version\":\"1.2\",\"chapters\":["
                        + "{\"startTime\":2,\"endTime\":1}]}");
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":-1}]}");
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":172800.001}]}");
    }

    @Test
    public void rejectsNonHttpsOrCredentialedChapterMetadataUrls() {
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                documentWithChapter("{\"startTime\":0,\"url\":\"http://example.com\"}"));
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                documentWithChapter(
                        "{\"startTime\":0,\"img\":\"https://user@example.com/x\"}"));
        assertReason(ChapterParseException.Reason.INVALID_SCHEMA,
                documentWithChapter("{\"startTime\":0,\"url\":\"\"}"));
    }

    @Test
    public void rejectsOversizedKnownFieldsAndNumberTokens() {
        assertReason(ChapterParseException.Reason.FIELD_LIMIT,
                documentWithChapter("{\"startTime\":0,\"title\":\""
                        + "a".repeat(SecurityLimits.MAX_CHAPTER_TITLE_CHARS + 1)
                        + "\"}"));
        assertReason(ChapterParseException.Reason.FIELD_LIMIT,
                documentWithChapter("{\"startTime\":" + "1".repeat(
                        SecurityLimits.MAX_CHAPTER_JSON_NUMBER_CHARS + 1) + "}"));
    }

    @Test
    public void rejectsChapterCountLimitPlusOne() {
        StringBuilder input = new StringBuilder("{\"version\":\"1.2\",\"chapters\":[");
        for (int index = 0; index <= SecurityLimits.MAX_CHAPTERS; index++) {
            if (index != 0) {
                input.append(',');
            }
            input.append("{\"startTime\":").append(index).append('}');
        }
        input.append("]}");

        assertReason(ChapterParseException.Reason.CHAPTER_LIMIT, input.toString());
    }

    @Test
    public void rejectsDepthAndTokenLimitPlusOne() {
        String nested = "0";
        for (int index = 0; index < SecurityLimits.MAX_CHAPTER_JSON_DEPTH; index++) {
            nested = '[' + nested + ']';
        }
        assertReason(ChapterParseException.Reason.DEPTH_LIMIT,
                "{\"version\":\"1.2\",\"chapters\":[],\"x\":" + nested + '}');

        StringBuilder tokens = new StringBuilder(
                "{\"version\":\"1.2\",\"chapters\":[],\"x\":[");
        for (int index = 0; index < SecurityLimits.MAX_CHAPTER_JSON_TOKENS; index++) {
            if (index != 0) {
                tokens.append(',');
            }
            tokens.append("null");
        }
        tokens.append("]}");
        assertReason(ChapterParseException.Reason.TOKEN_LIMIT, tokens.toString());
    }

    @Test
    public void rejectsResponseLimitPlusOne() {
        String input = "{\"version\":\"1.2\",\"chapters\":[],\"ignored\":\""
                + "a".repeat((int) SecurityLimits.MAX_CHAPTER_BYTES)
                + "\"}";

        assertReason(ChapterParseException.Reason.INPUT_LIMIT, input);
    }

    @Test
    public void rejectsMalformedJsonAndInvalidUtf8() {
        assertReason(ChapterParseException.Reason.MALFORMED_JSON,
                "{\"version\":\"1.2\",\"chapters\":[],}");
        assertReason(ChapterParseException.Reason.MALFORMED_JSON,
                "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":NaN}]}");
        assertReason(ChapterParseException.Reason.MALFORMED_JSON,
                "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":01}]}");
        assertReason(ChapterParseException.Reason.MALFORMED_JSON,
                "{\"version\":\"1.2\",\"chapters\":[{\"startTime\":0,"
                        + "\"title\":\"\\uD800\"}]}");

        byte[] prefix = documentWithChapter(
                "{\"startTime\":0,\"title\":\"").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}]}".getBytes(StandardCharsets.UTF_8);
        byte[] invalid = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, invalid, 0, prefix.length);
        invalid[prefix.length] = (byte) 0xc3;
        invalid[prefix.length + 1] = 0x28;
        System.arraycopy(suffix, 0, invalid, prefix.length + 2, suffix.length);
        ChapterParseException exception = assertThrows(
                ChapterParseException.class,
                () -> parser.parse(new ByteArrayInputStream(invalid)));
        assertEquals(ChapterParseException.Reason.MALFORMED_JSON, exception.getReason());
    }

    private PodcastChapterDocument parse(String input) throws Exception {
        return parser.parse(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertReason(ChapterParseException.Reason reason, String input) {
        ChapterParseException exception = assertThrows(
                ChapterParseException.class,
                () -> parse(input));
        assertEquals(reason, exception.getReason());
    }

    private static String documentWithChapter(String chapter) {
        return "{\"version\":\"1.2\",\"chapters\":[" + chapter + "]}";
    }
}
