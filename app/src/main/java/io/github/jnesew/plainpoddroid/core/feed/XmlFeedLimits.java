package io.github.jnesew.plainpoddroid.core.feed;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

final class XmlFeedLimits {
    final long expandedBytes;
    final int depth;
    final int attributes;
    final int attributeChars;
    final int episodes;
    final int aggregateTextChars;
    final int feedTitleChars;
    final int episodeTitleChars;
    final int authorChars;
    final int identifierChars;
    final int descriptionSourceChars;
    final int plainNotesChars;
    final int categories;
    final int categoryChars;
    final int enclosures;

    XmlFeedLimits(
            long expandedBytes,
            int depth,
            int attributes,
            int attributeChars,
            int episodes,
            int aggregateTextChars,
            int feedTitleChars,
            int episodeTitleChars,
            int authorChars,
            int identifierChars,
            int descriptionSourceChars,
            int plainNotesChars,
            int categories,
            int categoryChars,
            int enclosures) {
        this.expandedBytes = positive(expandedBytes, "expandedBytes");
        this.depth = positive(depth, "depth");
        this.attributes = positive(attributes, "attributes");
        this.attributeChars = positive(attributeChars, "attributeChars");
        this.episodes = positive(episodes, "episodes");
        this.aggregateTextChars = positive(aggregateTextChars, "aggregateTextChars");
        this.feedTitleChars = positive(feedTitleChars, "feedTitleChars");
        this.episodeTitleChars = positive(episodeTitleChars, "episodeTitleChars");
        this.authorChars = positive(authorChars, "authorChars");
        this.identifierChars = positive(identifierChars, "identifierChars");
        this.descriptionSourceChars = positive(
                descriptionSourceChars,
                "descriptionSourceChars");
        this.plainNotesChars = positive(plainNotesChars, "plainNotesChars");
        this.categories = positive(categories, "categories");
        this.categoryChars = positive(categoryChars, "categoryChars");
        this.enclosures = positive(enclosures, "enclosures");
    }

    static XmlFeedLimits production() {
        return new XmlFeedLimits(
                SecurityLimits.MAX_FEED_EXPANDED_BYTES,
                SecurityLimits.MAX_XML_DEPTH,
                SecurityLimits.MAX_XML_ATTRIBUTES,
                SecurityLimits.MAX_XML_ATTRIBUTE_CHARS,
                SecurityLimits.MAX_EPISODES_PER_FEED,
                SecurityLimits.MAX_XML_AGGREGATE_TEXT_CHARS,
                SecurityLimits.MAX_FEED_TITLE_CHARS,
                SecurityLimits.MAX_EPISODE_TITLE_CHARS,
                SecurityLimits.MAX_AUTHOR_CHARS,
                SecurityLimits.MAX_IDENTIFIER_CHARS,
                SecurityLimits.MAX_DESCRIPTION_SOURCE_CHARS,
                SecurityLimits.MAX_PLAIN_NOTES_CHARS,
                SecurityLimits.MAX_CATEGORIES_PER_ITEM,
                SecurityLimits.MAX_CATEGORY_CHARS,
                SecurityLimits.MAX_ENCLOSURES_PER_EPISODE);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
