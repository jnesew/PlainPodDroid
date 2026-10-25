package io.github.jnesew.plainpoddroid.core.feed;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

public final class FeedParser {
    private static final String FEATURE_VALIDATION =
            "http://xml.org/sax/features/validation";
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String FEATURE_LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String PROPERTY_LEXICAL_HANDLER =
            "http://xml.org/sax/properties/lexical-handler";

    private final XmlFeedLimits limits;

    public FeedParser() {
        this(XmlFeedLimits.production());
    }

    FeedParser(XmlFeedLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public FeedDocument parse(InputStream input) throws IOException, FeedParseException {
        Objects.requireNonNull(input, "input");
        Handler handler = new Handler(limits);
        XMLReader reader = createReader(handler);
        InputSource source = new InputSource(new BoundedInputStream(input, limits.expandedBytes));
        try {
            reader.parse(source);
            return handler.getDocument();
        } catch (LimitExceededIOException exception) {
            throw new FeedParseException(
                    FeedParseException.Reason.INPUT_LIMIT,
                    "Expanded feed exceeds the configured byte limit.",
                    exception);
        } catch (PolicySaxException exception) {
            throw exception.toFeedParseException();
        } catch (SAXParseException exception) {
            Throwable policyFailure = findCause(exception, PolicySaxException.class);
            if (policyFailure instanceof PolicySaxException policyException) {
                throw policyException.toFeedParseException();
            }
            Throwable byteFailure = findCause(exception, LimitExceededIOException.class);
            if (byteFailure instanceof LimitExceededIOException limitException) {
                throw new FeedParseException(
                        FeedParseException.Reason.INPUT_LIMIT,
                        "Expanded feed exceeds the configured byte limit.",
                        limitException);
            }
            throw new FeedParseException(
                    FeedParseException.Reason.MALFORMED_XML,
                    locationMessage(exception),
                    exception);
        } catch (SAXException exception) {
            Throwable policyFailure = findCause(exception, PolicySaxException.class);
            if (policyFailure instanceof PolicySaxException policyException) {
                throw policyException.toFeedParseException();
            }
            Throwable byteFailure = findCause(exception, LimitExceededIOException.class);
            if (byteFailure instanceof LimitExceededIOException limitException) {
                throw new FeedParseException(
                        FeedParseException.Reason.INPUT_LIMIT,
                        "Expanded feed exceeds the configured byte limit.",
                        limitException);
            }
            throw new FeedParseException(
                    FeedParseException.Reason.MALFORMED_XML,
                    "Malformed feed XML.",
                    exception);
        }
    }

    private static XMLReader createReader(Handler handler) throws FeedParseException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();

            setRequiredFeature(reader, FEATURE_VALIDATION, false);
            setRequiredFeature(reader, FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
            setRequiredFeature(reader, FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
            setOptionalFeature(reader, FEATURE_LOAD_EXTERNAL_DTD, false);
            setOptionalFeature(reader, XMLConstants.FEATURE_SECURE_PROCESSING, true);

            reader.setContentHandler(handler);
            reader.setDTDHandler(handler);
            reader.setEntityResolver(handler);
            reader.setErrorHandler(handler);
            reader.setProperty(PROPERTY_LEXICAL_HANDLER, handler);
            return reader;
        } catch (ParserConfigurationException
                | SAXNotRecognizedException
                | SAXNotSupportedException exception) {
            throw new FeedParseException(
                    FeedParseException.Reason.PARSER_CONFIGURATION,
                    "The platform XML parser cannot enforce the required security policy.",
                    exception);
        } catch (SAXException exception) {
            throw new FeedParseException(
                    FeedParseException.Reason.PARSER_CONFIGURATION,
                    "The platform XML parser could not be initialized.",
                    exception);
        }
    }

    private static void setRequiredFeature(XMLReader reader, String name, boolean value)
            throws SAXNotRecognizedException, SAXNotSupportedException {
        try {
            reader.setFeature(name, value);
        } catch (SAXNotRecognizedException | SAXNotSupportedException exception) {
            if (reader.getFeature(name) != value) {
                throw exception;
            }
        }
        if (reader.getFeature(name) != value) {
            throw new SAXNotSupportedException("Required SAX feature has the wrong value: " + name);
        }
    }

    private static void setOptionalFeature(XMLReader reader, String name, boolean value) {
        try {
            reader.setFeature(name, value);
        } catch (SAXNotRecognizedException | SAXNotSupportedException ignored) {
            // Android Expat intentionally supports a smaller feature set. The
            // required entity settings and lexical DTD callback remain mandatory.
        }
    }

    private static Throwable findCause(Throwable throwable, Class<?> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String locationMessage(SAXParseException exception) {
        if (exception.getLineNumber() <= 0) {
            return "Malformed feed XML.";
        }
        return "Malformed feed XML at line "
                + exception.getLineNumber()
                + ", column "
                + Math.max(exception.getColumnNumber(), 0)
                + ".";
    }

    private static final class Handler extends DefaultHandler2 {
        private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
        private static final String CONTENT_NAMESPACE =
                "http://purl.org/rss/1.0/modules/content/";
        private static final String DC_NAMESPACE = "http://purl.org/dc/elements/1.1/";
        private static final String ITUNES_NAMESPACE_HTTP =
                "http://www.itunes.com/dtds/podcast-1.0.dtd";
        private static final String ITUNES_NAMESPACE_HTTPS =
                "https://www.itunes.com/dtds/podcast-1.0.dtd";
        private static final String PODCAST_NAMESPACE =
                "https://podcastindex.org/namespace/1.0";
        private static final String PODCAST_NAMESPACE_DOCUMENT =
                "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md";

        private final XmlFeedLimits limits;
        private final ArrayDeque<Frame> stack = new ArrayDeque<>();
        private final FeedBuilder feed = new FeedBuilder();
        private EpisodeBuilder currentEpisode;
        private FeedDocument.Format format;
        private FeedDocument document;
        private long aggregateTextChars;
        private int episodeCount;
        private boolean rssChannelSeen;

        Handler(XmlFeedLimits limits) {
            this.limits = limits;
        }

        FeedDocument getDocument() throws FeedParseException {
            if (document == null) {
                throw new FeedParseException(
                        FeedParseException.Reason.MALFORMED_XML,
                        "Feed XML ended without a complete document.");
            }
            return document;
        }

        @Override
        public void startElement(
                String uri,
                String localName,
                String qualifiedName,
                Attributes attributes) throws SAXException {
            String name = elementName(localName, qualifiedName);
            String namespace = uri == null ? "" : uri;
            if (stack.size() >= limits.depth) {
                throw policy(
                        FeedParseException.Reason.DEPTH_LIMIT,
                        "Feed XML exceeds the element nesting limit.");
            }
            validateAttributes(attributes);

            if (stack.isEmpty()) {
                if (name.equals("rss")) {
                    format = FeedDocument.Format.RSS;
                } else if (name.equals("feed")
                        && (namespace.isEmpty() || namespace.equals(ATOM_NAMESPACE))) {
                    format = FeedDocument.Format.ATOM;
                } else {
                    throw policy(
                            FeedParseException.Reason.UNSUPPORTED_DOCUMENT,
                            "Document is not a supported RSS or Atom feed.");
                }
            }

            Frame parent = stack.peekLast();
            Frame grandparent = ancestor(1);
            boolean episodeRoot = false;
            if (isRssChannelStart(name, parent, grandparent)) {
                rssChannelSeen = true;
            } else if (isEpisodeStart(namespace, name, parent, grandparent)) {
                if (currentEpisode != null) {
                    throw policy(
                            FeedParseException.Reason.MALFORMED_XML,
                            "Nested episode elements are not allowed.");
                }
                episodeCount++;
                if (episodeCount > limits.episodes) {
                    throw policy(
                            FeedParseException.Reason.EPISODE_LIMIT,
                            "Feed exceeds the episode limit.");
                }
                currentEpisode = new EpisodeBuilder();
                episodeRoot = true;
            }

            processAttributeElement(namespace, name, attributes, parent, grandparent);
            Capture capture = resolveCapture(namespace, name, parent, grandparent);
            if (capture == null) {
                appendSeparatorToActiveCapture();
            }
            stack.addLast(new Frame(name, capture, episodeRoot));
        }

        @Override
        public void characters(char[] characters, int start, int length) throws SAXException {
            aggregateTextChars += length;
            if (aggregateTextChars > limits.aggregateTextChars) {
                throw policy(
                        FeedParseException.Reason.TEXT_LIMIT,
                        "Feed exceeds the aggregate text limit.");
            }
            Frame captureFrame = activeCapture();
            if (captureFrame != null) {
                captureFrame.append(characters, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qualifiedName)
                throws SAXException {
            String name = elementName(localName, qualifiedName);
            Frame frame = stack.peekLast();
            if (frame == null || !frame.name.equals(name)) {
                throw policy(
                        FeedParseException.Reason.MALFORMED_XML,
                        "Feed XML element structure is inconsistent.");
            }
            if (frame.capture != null) {
                applyCapture(frame.capture, frame.text.toString());
            }
            stack.removeLast();

            if (frame.episodeRoot) {
                if (currentEpisode == null) {
                    throw policy(
                            FeedParseException.Reason.MALFORMED_XML,
                            "Episode state is inconsistent.");
                }
                feed.episodes.add(currentEpisode.build(limits));
                currentEpisode = null;
            } else {
                appendSeparatorToActiveCapture();
            }
        }

        @Override
        public void endDocument() throws SAXException {
            if (!stack.isEmpty() || format == null || currentEpisode != null) {
                throw policy(
                        FeedParseException.Reason.MALFORMED_XML,
                        "Feed XML ended with incomplete elements.");
            }
            if (format == FeedDocument.Format.RSS && !rssChannelSeen) {
                throw policy(
                        FeedParseException.Reason.UNSUPPORTED_DOCUMENT,
                        "RSS document has no channel element.");
            }
            document = feed.build(format, limits);
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            throw policy(
                    FeedParseException.Reason.DTD_FORBIDDEN,
                    "XML document type declarations are forbidden.");
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
            throw policy(
                    FeedParseException.Reason.ENTITY_FORBIDDEN,
                    "External XML entities are forbidden.");
        }

        @Override
        public InputSource resolveEntity(
                String name,
                String publicId,
                String baseUri,
                String systemId) throws SAXException {
            throw policy(
                    FeedParseException.Reason.ENTITY_FORBIDDEN,
                    "External XML entities are forbidden.");
        }

        @Override
        public InputSource getExternalSubset(String name, String baseUri) {
            return null;
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            throw policy(
                    FeedParseException.Reason.ENTITY_FORBIDDEN,
                    "Unresolved XML entities are forbidden.");
        }

        @Override
        public void notationDecl(String name, String publicId, String systemId)
                throws SAXException {
            throw policy(
                    FeedParseException.Reason.DTD_FORBIDDEN,
                    "XML notation declarations are forbidden.");
        }

        @Override
        public void unparsedEntityDecl(
                String name,
                String publicId,
                String systemId,
                String notationName) throws SAXException {
            throw policy(
                    FeedParseException.Reason.DTD_FORBIDDEN,
                    "XML entity declarations are forbidden.");
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }

        private void validateAttributes(Attributes attributes) throws PolicySaxException {
            if (attributes.getLength() > limits.attributes) {
                throw policy(
                        FeedParseException.Reason.ATTRIBUTE_LIMIT,
                        "XML element exceeds the attribute-count limit.");
            }
            for (int index = 0; index < attributes.getLength(); index++) {
                String value = attributes.getValue(index);
                if (value != null && value.length() > limits.attributeChars) {
                    throw policy(
                            FeedParseException.Reason.ATTRIBUTE_VALUE_LIMIT,
                            "XML attribute exceeds the value-length limit.");
                }
            }
        }

        private boolean isRssChannelStart(String name, Frame parent, Frame grandparent) {
            return format == FeedDocument.Format.RSS
                    && name.equals("channel")
                    && parent != null
                    && parent.name.equals("rss")
                    && grandparent == null;
        }

        private boolean isEpisodeStart(String namespace, String name, Frame parent, Frame grandparent) {
            if (format == FeedDocument.Format.RSS) {
                // Extension items (including locked provider metadata) are not RSS episodes.
                return namespace.isEmpty() && name.equals("item")
                        && parent != null
                        && parent.name.equals("channel")
                        && grandparent != null
                        && grandparent.name.equals("rss");
            }
            return (namespace.isEmpty() || namespace.equals(ATOM_NAMESPACE)) && name.equals("entry")
                    && parent != null
                    && parent.name.equals("feed")
                    && grandparent == null;
        }

        private void processAttributeElement(
                String uri,
                String name,
                Attributes attributes,
                Frame parent,
                Frame grandparent) throws SAXException {
            boolean feedChild = isDirectFeedChild(parent, grandparent);
            boolean episodeChild = isDirectEpisodeChild(parent);
            if (episodeChild && name.equals("chapters") && isPodcastNamespace(uri)) {
                currentEpisode.assignChapterReference(
                        attribute(attributes, "url"),
                        attribute(attributes, "type"));
                return;
            }
            if (format == FeedDocument.Format.RSS) {
                if (episodeChild && name.equals("enclosure")) {
                    addEnclosure(
                            attribute(attributes, "url"),
                            attribute(attributes, "type"),
                            attribute(attributes, "length"));
                } else if (feedChild && name.equals("image") && isItunes(uri)) {
                    assignArtworkIfPresent(feed, attribute(attributes, "href"));
                }
                return;
            }

            if (name.equals("link") && (feedChild || episodeChild)) {
                String relation = attribute(attributes, "rel");
                String href = attribute(attributes, "href");
                if (relation.isEmpty()) {
                    relation = "alternate";
                }
                if (episodeChild && relation.equalsIgnoreCase("enclosure")) {
                    addEnclosure(href, attribute(attributes, "type"),
                            attribute(attributes, "length"));
                } else if (relation.equalsIgnoreCase("alternate")) {
                    if (episodeChild) {
                        currentEpisode.assignWebsite(href);
                    } else {
                        feed.assignWebsite(href);
                    }
                }
            } else if (name.equals("category") && (feedChild || episodeChild)) {
                String term = attribute(attributes, "term");
                if (!term.isEmpty()) {
                    addCategory(term, episodeChild);
                }
            }
        }

        private Capture resolveCapture(
                String uri,
                String name,
                Frame parent,
                Frame grandparent) {
            boolean feedChild = isDirectFeedChild(parent, grandparent);
            boolean episodeChild = isDirectEpisodeChild(parent);
            if (format == FeedDocument.Format.RSS) {
                if (feedChild) {
                    if (name.equals("title")) {
                        return Capture.of(CaptureKind.FEED_TITLE, limits.feedTitleChars, 1);
                    }
                    if (name.equals("description")) {
                        return Capture.of(
                                CaptureKind.FEED_DESCRIPTION,
                                limits.descriptionSourceChars,
                                2);
                    }
                    if (name.equals("summary") && isItunes(uri)) {
                        return Capture.of(
                                CaptureKind.FEED_DESCRIPTION,
                                limits.descriptionSourceChars,
                                1);
                    }
                    if (name.equals("link")) {
                        return Capture.of(
                                CaptureKind.FEED_WEBSITE,
                                limits.attributeChars,
                                1);
                    }
                    if (name.equals("author") && isItunes(uri)) {
                        return Capture.of(CaptureKind.FEED_AUTHOR, limits.authorChars, 2);
                    }
                    if (name.equals("managingeditor")
                            || name.equals("author")
                            || name.equals("creator") && uri.equals(DC_NAMESPACE)) {
                        return Capture.of(CaptureKind.FEED_AUTHOR, limits.authorChars, 1);
                    }
                    if (name.equals("category")) {
                        return Capture.of(
                                CaptureKind.FEED_CATEGORY,
                                limits.categoryChars,
                                1);
                    }
                } else if (episodeChild) {
                    if (name.equals("title")) {
                        return Capture.of(
                                CaptureKind.EPISODE_TITLE,
                                limits.episodeTitleChars,
                                1);
                    }
                    if (name.equals("guid")) {
                        return Capture.of(
                                CaptureKind.EPISODE_IDENTIFIER,
                                limits.identifierChars,
                                1);
                    }
                    if (name.equals("encoded") && uri.equals(CONTENT_NAMESPACE)) {
                        return Capture.of(
                                CaptureKind.EPISODE_DESCRIPTION,
                                limits.descriptionSourceChars,
                                3);
                    }
                    if (name.equals("description")) {
                        return Capture.of(
                                CaptureKind.EPISODE_DESCRIPTION,
                                limits.descriptionSourceChars,
                                2);
                    }
                    if (name.equals("summary") && isItunes(uri)) {
                        return Capture.of(
                                CaptureKind.EPISODE_DESCRIPTION,
                                limits.descriptionSourceChars,
                                1);
                    }
                    if (name.equals("link")) {
                        return Capture.of(
                                CaptureKind.EPISODE_WEBSITE,
                                limits.attributeChars,
                                1);
                    }
                    if (name.equals("author") && isItunes(uri)) {
                        return Capture.of(CaptureKind.EPISODE_AUTHOR, limits.authorChars, 2);
                    }
                    if (name.equals("author")
                            || name.equals("creator") && uri.equals(DC_NAMESPACE)) {
                        return Capture.of(CaptureKind.EPISODE_AUTHOR, limits.authorChars, 1);
                    }
                    if (name.equals("pubdate")) {
                        return Capture.of(
                                CaptureKind.EPISODE_PUBLISHED,
                                limits.attributeChars,
                                1);
                    }
                    if (name.equals("duration") && isItunes(uri)) {
                        return Capture.of(CaptureKind.EPISODE_DURATION, 64, 1);
                    }
                    if (name.equals("category")) {
                        return Capture.of(
                                CaptureKind.EPISODE_CATEGORY,
                                limits.categoryChars,
                                1);
                    }
                } else if (name.equals("url")
                        && parent != null
                        && parent.name.equals("image")
                        && grandparent != null
                        && grandparent.name.equals("channel")) {
                    return Capture.of(
                            CaptureKind.FEED_ARTWORK,
                            limits.attributeChars,
                            1);
                }
                return null;
            }

            if (feedChild) {
                if (name.equals("title")) {
                    return Capture.of(CaptureKind.FEED_TITLE, limits.feedTitleChars, 1);
                }
                if (name.equals("subtitle")) {
                    return Capture.of(
                            CaptureKind.FEED_DESCRIPTION,
                            limits.descriptionSourceChars,
                            1);
                }
                if (name.equals("logo") || name.equals("icon")) {
                    return Capture.of(
                            CaptureKind.FEED_ARTWORK,
                            limits.attributeChars,
                            1);
                }
                if (name.equals("category")) {
                    return Capture.of(
                            CaptureKind.FEED_CATEGORY,
                            limits.categoryChars,
                            1);
                }
            } else if (episodeChild) {
                if (name.equals("title")) {
                    return Capture.of(
                            CaptureKind.EPISODE_TITLE,
                            limits.episodeTitleChars,
                            1);
                }
                if (name.equals("id")) {
                    return Capture.of(
                            CaptureKind.EPISODE_IDENTIFIER,
                            limits.identifierChars,
                            1);
                }
                if (name.equals("content")) {
                    return Capture.of(
                            CaptureKind.EPISODE_DESCRIPTION,
                            limits.descriptionSourceChars,
                            3);
                }
                if (name.equals("summary")) {
                    return Capture.of(
                            CaptureKind.EPISODE_DESCRIPTION,
                            limits.descriptionSourceChars,
                            2);
                }
                if (name.equals("published")) {
                    return Capture.of(
                            CaptureKind.EPISODE_PUBLISHED,
                            limits.attributeChars,
                            2);
                }
                if (name.equals("updated")) {
                    return Capture.of(
                            CaptureKind.EPISODE_PUBLISHED,
                            limits.attributeChars,
                            1);
                }
                if (name.equals("category")) {
                    return Capture.of(
                            CaptureKind.EPISODE_CATEGORY,
                            limits.categoryChars,
                            1);
                }
            } else if (name.equals("name")
                    && parent != null
                    && parent.name.equals("author")
                    && grandparent != null) {
                if (currentEpisode != null && grandparent.episodeRoot) {
                    return Capture.of(CaptureKind.EPISODE_AUTHOR, limits.authorChars, 1);
                }
                if (currentEpisode == null && grandparent.name.equals("feed")) {
                    return Capture.of(CaptureKind.FEED_AUTHOR, limits.authorChars, 1);
                }
            }
            return null;
        }

        private void applyCapture(Capture capture, String rawValue) throws SAXException {
            String value;
            if (capture.kind == CaptureKind.FEED_DESCRIPTION
                    || capture.kind == CaptureKind.EPISODE_DESCRIPTION) {
                value = rawValue;
            } else if (capture.kind == CaptureKind.FEED_WEBSITE
                    || capture.kind == CaptureKind.EPISODE_WEBSITE
                    || capture.kind == CaptureKind.FEED_ARTWORK) {
                value = normalizeUrlText(rawValue, capture.limit);
            } else {
                value = sanitizeField(rawValue, capture.limit);
            }
            if (value.isEmpty()) {
                return;
            }

            switch (capture.kind) {
                case FEED_TITLE -> feed.assignTitle(value);
                case FEED_AUTHOR -> feed.assignAuthor(value, capture.priority);
                case FEED_DESCRIPTION -> feed.assignDescription(value, capture.priority);
                case FEED_WEBSITE -> feed.assignWebsite(value);
                case FEED_ARTWORK -> feed.assignArtwork(value);
                case FEED_CATEGORY -> feed.addCategory(value, limits);
                case EPISODE_TITLE -> currentEpisode.assignTitle(value);
                case EPISODE_IDENTIFIER -> currentEpisode.assignIdentifier(value);
                case EPISODE_AUTHOR -> currentEpisode.assignAuthor(value, capture.priority);
                case EPISODE_DESCRIPTION ->
                        currentEpisode.assignDescription(value, capture.priority);
                case EPISODE_WEBSITE -> currentEpisode.assignWebsite(value);
                case EPISODE_PUBLISHED ->
                        currentEpisode.assignPublished(value, capture.priority);
                case EPISODE_DURATION -> currentEpisode.assignDuration(value);
                case EPISODE_CATEGORY -> currentEpisode.addCategory(value, limits);
            }
        }

        private String sanitizeField(String rawValue, int limit) throws PolicySaxException {
            StringBuilder output = new StringBuilder(Math.min(rawValue.length(), limit));
            boolean pendingSpace = false;
            for (int index = 0; index < rawValue.length();) {
                int codePoint = rawValue.codePointAt(index);
                index += Character.charCount(codePoint);
                if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                    pendingSpace = output.length() != 0;
                    continue;
                }
                if (pendingSpace) {
                    if (output.length() >= limit) {
                        throw policy(
                                FeedParseException.Reason.FIELD_LIMIT,
                                "Feed field exceeds its normalized text limit.");
                    }
                    output.append(' ');
                    pendingSpace = false;
                }
                int required = Character.charCount(codePoint);
                if (output.length() > limit - required) {
                    throw policy(
                            FeedParseException.Reason.FIELD_LIMIT,
                            "Feed field exceeds its normalized text limit.");
                }
                output.appendCodePoint(codePoint);
            }
            String normalized = Normalizer.normalize(output, Normalizer.Form.NFC);
            if (normalized.length() > limit) {
                throw policy(
                        FeedParseException.Reason.FIELD_LIMIT,
                        "Feed field exceeds its normalized text limit.");
            }
            return normalized;
        }

        private String normalizeUrlText(String rawValue, int limit) throws PolicySaxException {
            String normalized = Normalizer.normalize(rawValue.trim(), Normalizer.Form.NFC);
            if (normalized.length() > limit) {
                throw policy(
                        FeedParseException.Reason.FIELD_LIMIT,
                        "Feed URL field exceeds its text limit.");
            }
            return normalized;
        }

        private void addEnclosure(String url, String type, String length) {
            if (currentEpisode == null || url.isEmpty()) {
                return;
            }
            if (currentEpisode.enclosures.size() >= limits.enclosures) {
                return;
            }
            currentEpisode.enclosures.add(new FeedDocument.Enclosure(
                    url.trim(),
                    type.trim().toLowerCase(Locale.ROOT),
                    parseNonNegativeLong(length)));
        }

        private void addCategory(String rawCategory, boolean episode) throws SAXException {
            String category = sanitizeField(rawCategory, limits.categoryChars);
            if (category.isEmpty()) {
                return;
            }
            if (episode) {
                currentEpisode.addCategory(category, limits);
            } else {
                feed.addCategory(category, limits);
            }
        }

        private void assignArtworkIfPresent(FeedBuilder builder, String value) {
            if (value.isEmpty()) {
                return;
            }
            builder.assignArtwork(value.trim());
        }

        private boolean isDirectFeedChild(Frame parent, Frame grandparent) {
            if (currentEpisode != null || parent == null) {
                return false;
            }
            if (format == FeedDocument.Format.RSS) {
                return parent.name.equals("channel")
                        && grandparent != null
                        && grandparent.name.equals("rss");
            }
            return parent.name.equals("feed") && grandparent == null;
        }

        private boolean isDirectEpisodeChild(Frame parent) {
            return currentEpisode != null && parent != null && parent.episodeRoot;
        }

        private Frame ancestor(int distanceFromParent) {
            Iterator<Frame> iterator = stack.descendingIterator();
            Frame result = null;
            for (int index = 0; index <= distanceFromParent; index++) {
                if (!iterator.hasNext()) {
                    return null;
                }
                result = iterator.next();
            }
            return result;
        }

        private Frame activeCapture() {
            Iterator<Frame> iterator = stack.descendingIterator();
            while (iterator.hasNext()) {
                Frame frame = iterator.next();
                if (frame.capture != null) {
                    return frame;
                }
            }
            return null;
        }

        private void appendSeparatorToActiveCapture() throws PolicySaxException {
            Frame frame = activeCapture();
            if (frame != null) {
                frame.append(' ');
            }
        }

        private static String attribute(Attributes attributes, String name) {
            for (int index = 0; index < attributes.getLength(); index++) {
                String candidate = elementName(
                        attributes.getLocalName(index),
                        attributes.getQName(index));
                if (candidate.equals(name)) {
                    String value = attributes.getValue(index);
                    return value == null ? "" : value;
                }
            }
            return "";
        }

        private static boolean isItunes(String uri) {
            return uri.equals(ITUNES_NAMESPACE_HTTP) || uri.equals(ITUNES_NAMESPACE_HTTPS);
        }

        private static boolean isPodcastNamespace(String uri) {
            return uri.equals(PODCAST_NAMESPACE) || uri.equals(PODCAST_NAMESPACE_DOCUMENT);
        }

        private static long parseNonNegativeLong(String value) {
            if (value == null || value.trim().isEmpty()) {
                return -1L;
            }
            try {
                long parsed = Long.parseLong(value.trim());
                return parsed < 0 ? -1L : parsed;
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }

        private static String elementName(String localName, String qualifiedName) {
            String candidate = localName == null || localName.isEmpty()
                    ? qualifiedName
                    : localName;
            if (candidate == null) {
                return "";
            }
            int colon = candidate.indexOf(':');
            if (colon >= 0 && colon + 1 < candidate.length()) {
                candidate = candidate.substring(colon + 1);
            }
            return candidate.toLowerCase(Locale.ROOT);
        }

        private static PolicySaxException policy(
                FeedParseException.Reason reason,
                String message) {
            return new PolicySaxException(reason, message);
        }
    }

    private enum CaptureKind {
        FEED_TITLE,
        FEED_AUTHOR,
        FEED_DESCRIPTION,
        FEED_WEBSITE,
        FEED_ARTWORK,
        FEED_CATEGORY,
        EPISODE_TITLE,
        EPISODE_IDENTIFIER,
        EPISODE_AUTHOR,
        EPISODE_DESCRIPTION,
        EPISODE_WEBSITE,
        EPISODE_PUBLISHED,
        EPISODE_DURATION,
        EPISODE_CATEGORY
    }

    private static final class Capture {
        final CaptureKind kind;
        final int limit;
        final int priority;

        private Capture(CaptureKind kind, int limit, int priority) {
            this.kind = kind;
            this.limit = limit;
            this.priority = priority;
        }

        static Capture of(CaptureKind kind, int limit, int priority) {
            return new Capture(kind, limit, priority);
        }
    }

    private static final class Frame {
        final String name;
        final Capture capture;
        final boolean episodeRoot;
        final StringBuilder text;

        Frame(String name, Capture capture, boolean episodeRoot) {
            this.name = name;
            this.capture = capture;
            this.episodeRoot = episodeRoot;
            this.text = capture == null ? null : new StringBuilder();
        }

        void append(char[] characters, int start, int length) throws PolicySaxException {
            if (text.length() > capture.limit - length) {
                throw new PolicySaxException(
                        FeedParseException.Reason.FIELD_LIMIT,
                        "Feed field exceeds its source text limit.");
            }
            text.append(characters, start, length);
        }

        void append(char value) throws PolicySaxException {
            if (text.length() >= capture.limit) {
                throw new PolicySaxException(
                        FeedParseException.Reason.FIELD_LIMIT,
                        "Feed field exceeds its source text limit.");
            }
            text.append(value);
        }
    }

    private static final class FeedBuilder {
        String title;
        String author;
        int authorPriority;
        String rawDescription;
        int descriptionPriority;
        String websiteUrl;
        String artworkUrl;
        final List<String> categories = new ArrayList<>();
        final List<FeedDocument.Episode> episodes = new ArrayList<>();

        void assignTitle(String value) {
            if (title == null) {
                title = value;
            }
        }

        void assignAuthor(String value, int priority) {
            if (author == null || priority > authorPriority) {
                author = value;
                authorPriority = priority;
            }
        }

        void assignDescription(String value, int priority) {
            if (rawDescription == null || priority > descriptionPriority) {
                rawDescription = value;
                descriptionPriority = priority;
            }
        }

        void assignWebsite(String value) {
            if (websiteUrl == null && value != null && !value.trim().isEmpty()) {
                websiteUrl = value.trim();
            }
        }

        void assignArtwork(String value) {
            if (artworkUrl == null && value != null && !value.trim().isEmpty()) {
                artworkUrl = value.trim();
            }
        }

        void addCategory(String value, XmlFeedLimits limits) throws PolicySaxException {
            addBoundedCategory(categories, value, limits);
        }

        FeedDocument build(FeedDocument.Format format, XmlFeedLimits limits)
                throws PolicySaxException {
            return new FeedDocument(
                    format,
                    title,
                    author,
                    sanitizeNotes(rawDescription, limits),
                    websiteUrl,
                    artworkUrl,
                    categories,
                    episodes);
        }
    }

    private static final class EpisodeBuilder {
        String title;
        String identifier;
        String author;
        int authorPriority;
        String rawDescription;
        int descriptionPriority;
        String websiteUrl;
        String published;
        int publishedPriority;
        String duration;
        String chapterUrl;
        String chapterMimeType;
        boolean chapterReferenceSeen;
        boolean chapterReferenceAmbiguous;
        final List<String> categories = new ArrayList<>();
        final List<FeedDocument.Enclosure> enclosures = new ArrayList<>();

        void assignTitle(String value) {
            if (title == null) {
                title = value;
            }
        }

        void assignIdentifier(String value) {
            if (identifier == null) {
                identifier = value;
            }
        }

        void assignAuthor(String value, int priority) {
            if (author == null || priority > authorPriority) {
                author = value;
                authorPriority = priority;
            }
        }

        void assignDescription(String value, int priority) {
            if (rawDescription == null || priority > descriptionPriority) {
                rawDescription = value;
                descriptionPriority = priority;
            }
        }

        void assignWebsite(String value) {
            if (websiteUrl == null && value != null && !value.trim().isEmpty()) {
                websiteUrl = value.trim();
            }
        }

        void assignPublished(String value, int priority) {
            if (published == null || priority > publishedPriority) {
                published = value;
                publishedPriority = priority;
            }
        }

        void assignDuration(String value) {
            if (duration == null) {
                duration = value;
            }
        }

        void assignChapterReference(String url, String mimeType) {
            if (chapterReferenceSeen) {
                chapterReferenceAmbiguous = true;
                chapterUrl = null;
                chapterMimeType = null;
                return;
            }
            chapterReferenceSeen = true;
            chapterUrl = url;
            chapterMimeType = mimeType;
        }

        void addCategory(String value, XmlFeedLimits limits) throws PolicySaxException {
            addBoundedCategory(categories, value, limits);
        }

        FeedDocument.Episode build(XmlFeedLimits limits) throws PolicySaxException {
            return new FeedDocument.Episode(
                    title,
                    identifier,
                    author,
                    sanitizeNotes(rawDescription, limits),
                    websiteUrl,
                    published,
                    duration,
                    chapterReferenceAmbiguous ? null : chapterUrl,
                    chapterReferenceAmbiguous ? null : chapterMimeType,
                    categories,
                    enclosures);
        }
    }

    private static String sanitizeNotes(String rawDescription, XmlFeedLimits limits)
            throws PolicySaxException {
        try {
            return PlainTextSanitizer.sanitizeNotes(rawDescription, limits.plainNotesChars);
        } catch (PlainTextSanitizer.LimitException exception) {
            throw new PolicySaxException(
                    FeedParseException.Reason.FIELD_LIMIT,
                    "Feed notes exceed the stored plain-text limit.");
        }
    }

    private static void addBoundedCategory(
            List<String> categories,
            String value,
            XmlFeedLimits limits) throws PolicySaxException {
        if (categories.size() >= limits.categories) {
            throw new PolicySaxException(
                    FeedParseException.Reason.FIELD_LIMIT,
                    "Feed exceeds the category-count limit.");
        }
        categories.add(value);
    }

    private static final class PolicySaxException extends SAXException {
        private static final long serialVersionUID = 1L;

        private final FeedParseException.Reason reason;

        PolicySaxException(FeedParseException.Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        FeedParseException toFeedParseException() {
            return new FeedParseException(reason, getMessage(), this);
        }
    }

    private static final class LimitExceededIOException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        BoundedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value < 0) {
                return -1;
            }
            if (count >= limit) {
                throw new LimitExceededIOException();
            }
            count++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if ((offset | length) < 0 || length > buffer.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            long remaining = limit - count;
            int requested = (int) Math.min((long) length, Math.max(remaining + 1L, 1L));
            int read = super.read(buffer, offset, requested);
            if (read < 0) {
                return -1;
            }
            if (read > remaining) {
                throw new LimitExceededIOException();
            }
            count += read;
            return read;
        }

        @Override
        public long skip(long byteCount) throws IOException {
            if (byteCount <= 0) {
                return 0;
            }
            long remaining = limit - count;
            long skipped = super.skip(Math.min(byteCount, Math.max(remaining + 1L, 1L)));
            if (skipped > remaining) {
                throw new LimitExceededIOException();
            }
            count += skipped;
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readLimit) {
            // Rewinding would bypass the monotonic byte counter.
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark/reset is not supported");
        }

        @Override
        public void close() {
            // The parser may close its InputSource. Ownership of the network or
            // decompression stream remains with the caller.
        }
    }
}
