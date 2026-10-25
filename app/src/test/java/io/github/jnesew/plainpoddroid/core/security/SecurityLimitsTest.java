package io.github.jnesew.plainpoddroid.core.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SecurityLimitsTest {
    @Test
    public void feedWireLimitCoversIdentityResponses() {
        assertTrue(SecurityLimits.MAX_FEED_EXPANDED_BYTES
                >= SecurityLimits.MAX_FEED_COMPRESSED_BYTES);
    }

    @Test
    public void documentedNetworkAndParserCeilingsRemainFixed() {
        assertEquals(4_096, SecurityLimits.MAX_URL_CHARS);
        assertEquals(5, SecurityLimits.MAX_REDIRECTS);
        assertEquals(12, SecurityLimits.MAX_MEDIA_REDIRECTS);
        assertEquals(10, SecurityLimits.FEED_CONNECT_TIMEOUT_SECONDS);
        assertEquals(15, SecurityLimits.FEED_RESPONSE_HEADER_TIMEOUT_SECONDS);
        assertEquals(20, SecurityLimits.FEED_READ_TIMEOUT_SECONDS);
        assertEquals(30, SecurityLimits.FEED_CALL_TIMEOUT_SECONDS);
        assertEquals(4, SecurityLimits.MAX_CONCURRENT_NETWORK_CALLS);
        assertEquals(2, SecurityLimits.MAX_CONCURRENT_FEED_CALLS);
        assertEquals(2, SecurityLimits.MAX_CONCURRENT_MEDIA_CALLS);
        assertEquals(20, SecurityLimits.MEDIA_RESPONSE_HEADER_TIMEOUT_SECONDS);
        assertEquals(30, SecurityLimits.MEDIA_READ_TIMEOUT_SECONDS);
        assertEquals(8, SecurityLimits.MAX_PENDING_SUBSCRIPTION_PREVIEWS);
        assertEquals(600_000L, SecurityLimits.SUBSCRIPTION_PREVIEW_TTL_MILLIS);
        assertEquals(64, SecurityLimits.MAX_XML_DEPTH);
        assertEquals(64, SecurityLimits.MAX_XML_ATTRIBUTES);
        assertEquals(4_096, SecurityLimits.MAX_XML_ATTRIBUTE_CHARS);
        assertEquals(24 * 1024 * 1024, SecurityLimits.MAX_XML_AGGREGATE_TEXT_CHARS);
        assertEquals(10_000, SecurityLimits.MAX_EPISODES_PER_FEED);
        assertEquals(10_000, SecurityLimits.MAX_EPISODES_PER_SUBSCRIPTION);
        assertEquals(32L * 1024 * 1024, SecurityLimits.MAX_FEED_COMPRESSED_BYTES);
        assertEquals(32L * 1024 * 1024, SecurityLimits.MAX_FEED_EXPANDED_BYTES);
        assertEquals(256 * 1024, SecurityLimits.MAX_APPLE_LOOKUP_BYTES);
        assertEquals(512, SecurityLimits.MAX_FEED_TITLE_CHARS);
        assertEquals(1_024, SecurityLimits.MAX_EPISODE_TITLE_CHARS);
        assertEquals(256, SecurityLimits.MAX_AUTHOR_CHARS);
        assertEquals(2_048, SecurityLimits.MAX_IDENTIFIER_CHARS);
        assertEquals(512 * 1024, SecurityLimits.MAX_DESCRIPTION_SOURCE_CHARS);
        assertEquals(128 * 1024, SecurityLimits.MAX_PLAIN_NOTES_CHARS);
        assertEquals(64, SecurityLimits.MAX_CATEGORIES_PER_ITEM);
        assertEquals(128, SecurityLimits.MAX_CATEGORY_CHARS);
        assertEquals(8, SecurityLimits.MAX_ENCLOSURES_PER_EPISODE);
        assertEquals(8_256, SecurityLimits.MAX_STORED_CATEGORY_LIST_CHARS);
        assertEquals(127, SecurityLimits.MAX_MIME_TYPE_CHARS);
        assertEquals(1_000, SecurityLimits.MAX_SUBSCRIPTIONS);
        assertEquals(50, SecurityLimits.CATALOG_BROWSE_PAGE_SIZE);
        assertEquals(200, SecurityLimits.MAX_CATALOG_BROWSE_PAGE_SIZE);
        assertEquals(200, SecurityLimits.CATALOG_BROWSE_VISIBLE_EPISODE_LIMIT);
        assertEquals(2_000, SecurityLimits.MAX_QUEUE_ENTRIES);
        assertEquals(25, SecurityLimits.QUEUE_BROWSE_PAGE_SIZE);
    }

    @Test
    public void downloadLimitUsesLongArithmetic() {
        assertEquals(2_147_483_648L, SecurityLimits.MAX_DOWNLOAD_BYTES);
        assertEquals(536_870_912L, SecurityLimits.MIN_DOWNLOAD_QUOTA_BYTES);
        assertEquals(2_147_483_648L, SecurityLimits.DEFAULT_DOWNLOAD_QUOTA_BYTES);
        assertEquals(21_474_836_480L, SecurityLimits.MAX_DOWNLOAD_QUOTA_BYTES);
        assertEquals(86_400_000L, SecurityLimits.MAX_DOWNLOAD_ACTIVE_MILLIS);
        assertEquals(86_400_000L, SecurityLimits.DOWNLOAD_TEMP_MAX_AGE_MILLIS);
        assertEquals(400_000, SecurityLimits.MAX_DOWNLOAD_DIRECTORY_ENTRIES);
        assertEquals(172_800_000L, SecurityLimits.MAX_MEDIA_DURATION_MILLIS);
        assertEquals(15_000L, SecurityLimits.PLAYBACK_PROGRESS_WRITE_INTERVAL_MILLIS);
        assertEquals(300_000L,
                SecurityLimits.PLAYBACK_COMPLETION_MINIMUM_POSITION_MILLIS);
        assertEquals(120_000L, SecurityLimits.PLAYBACK_COMPLETION_REMAINING_MILLIS);
        assertEquals(95, SecurityLimits.PLAYBACK_COMPLETION_PERCENT);
        assertEquals(5, SecurityLimits.MIN_SLEEP_TIMER_MINUTES);
        assertEquals(60, SecurityLimits.MAX_SLEEP_TIMER_MINUTES);
        assertEquals(524_288L, SecurityLimits.MAX_CHAPTER_BYTES);
        assertEquals(1_000, SecurityLimits.MAX_CHAPTERS);
        assertEquals(200, SecurityLimits.MAX_VISIBLE_CHAPTERS);
        assertEquals(256, SecurityLimits.MAX_CHAPTER_TITLE_CHARS);
        assertEquals(16, SecurityLimits.MAX_CHAPTER_JSON_DEPTH);
        assertEquals(20_000, SecurityLimits.MAX_CHAPTER_JSON_TOKENS);
    }
}
