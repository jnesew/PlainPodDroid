package io.github.jnesew.plainpoddroid.core.opml;

import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

/** Bounded, inert OPML 2.0 discovery. The caller retains ownership of the input stream. */
public final class OpmlParser {
    private static final String VALIDATION = "http://xml.org/sax/features/validation";
    private static final String EXTERNAL_GENERAL =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String LEXICAL_HANDLER =
            "http://xml.org/sax/properties/lexical-handler";

    public OpmlDocument parse(InputStream input) throws IOException, OpmlParseException {
        if (input == null) {
            throw new NullPointerException("input");
        }
        String xml = readUtf8(input);
        Handler handler = new Handler();
        XMLReader reader = newReader(handler);
        try {
            reader.parse(new InputSource(new StringReader(xml)));
            return handler.document();
        } catch (SAXException exception) {
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof OpmlParseException policy) {
                    throw policy;
                }
                Throwable next = cause.getCause();
                // Android SAXException can retain its embedded exception only
                // through getException(), unlike the host JDK's cause chain.
                if (next == null && cause instanceof SAXException sax) next = sax.getException();
                if (next == cause) break;
                cause = next;
            }
            throw new OpmlParseException(OpmlParseException.Reason.MALFORMED_XML,
                    "Malformed OPML XML.");
        }
    }

    private static String readUtf8(InputStream input) throws IOException, OpmlParseException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                int single = input.read();
                if (single == -1) {
                    break;
                }
                if (bytes.size() >= SecurityLimits.MAX_OPML_BYTES) {
                    throw policy(OpmlParseException.Reason.INPUT_LIMIT);
                }
                bytes.write(single);
            } else {
                if (read > SecurityLimits.MAX_OPML_BYTES - bytes.size()) {
                    throw policy(OpmlParseException.Reason.INPUT_LIMIT);
                }
                bytes.write(buffer, 0, read);
            }
        }
        try {
            String xml = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return xml.startsWith("\ufeff") ? xml.substring(1) : xml;
        } catch (CharacterCodingException exception) {
            throw policy(OpmlParseException.Reason.INVALID_ENCODING);
        }
    }

    private static XMLReader newReader(Handler handler) throws OpmlParseException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            required(reader, VALIDATION, false);
            required(reader, EXTERNAL_GENERAL, false);
            required(reader, EXTERNAL_PARAMETER, false);
            optional(reader, EXTERNAL_DTD, false);
            optional(reader, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            reader.setContentHandler(handler);
            reader.setDTDHandler(handler);
            reader.setEntityResolver(handler);
            reader.setErrorHandler(handler);
            reader.setProperty(LEXICAL_HANDLER, handler);
            return reader;
        } catch (ParserConfigurationException | SAXException exception) {
            throw policy(OpmlParseException.Reason.PARSER_CONFIGURATION);
        }
    }

    private static void required(XMLReader reader, String feature, boolean value)
            throws SAXNotRecognizedException, SAXNotSupportedException {
        try {
            reader.setFeature(feature, value);
        } catch (SAXNotRecognizedException | SAXNotSupportedException exception) {
            if (reader.getFeature(feature) != value) {
                throw exception;
            }
        }
        if (reader.getFeature(feature) != value) {
            throw new SAXNotSupportedException("Required SAX feature is unavailable.");
        }
    }

    private static void optional(XMLReader reader, String feature, boolean value) {
        try {
            reader.setFeature(feature, value);
        } catch (SAXNotRecognizedException | SAXNotSupportedException ignored) {
            // Android Expat has fewer features; the entity and lexical guards above are required.
        }
    }

    private static OpmlParseException policy(OpmlParseException.Reason reason) {
        return new OpmlParseException(reason, "OPML violates a size, structure, or XML policy.");
    }

    private static final class Handler extends DefaultHandler2 {
        private final ArrayDeque<String> stack = new ArrayDeque<>();
        private final List<OpmlDocument.Feed> feeds = new ArrayList<>();
        private final Set<CanonicalHttpsUrl> seen = new HashSet<>();
        private int outlineDepth;
        private int outlineCount;
        private int candidateCount;
        private int rejectedCount;
        private int duplicateCount;
        private boolean bodySeen;

        @Override
        public void startElement(String uri, String local, String qName, Attributes attrs)
                throws SAXException {
            String name = local.isEmpty() ? qName : local;
            String parent = stack.peek();
            if (stack.size() > SecurityLimits.MAX_OPML_DEPTH + 2) {
                fail(OpmlParseException.Reason.DEPTH_LIMIT);
            }
            if (!uri.isEmpty() || attrs.getLength() > SecurityLimits.MAX_XML_ATTRIBUTES) {
                fail(OpmlParseException.Reason.ATTRIBUTE_LIMIT);
            }
            for (int index = 0; index < attrs.getLength(); index++) {
                if (attrs.getValue(index).length() > SecurityLimits.MAX_XML_ATTRIBUTE_CHARS) {
                    fail(OpmlParseException.Reason.ATTRIBUTE_LIMIT);
                }
            }
            if (parent == null) {
                if (!name.equals("opml")) {
                    fail(OpmlParseException.Reason.STRUCTURE);
                }
            } else if (parent.equals("opml")) {
                if (name.equals("body")) {
                    if (bodySeen) {
                        fail(OpmlParseException.Reason.STRUCTURE);
                    }
                    bodySeen = true;
                } else if (!name.equals("head") || bodySeen) {
                    fail(OpmlParseException.Reason.STRUCTURE);
                }
            } else if (parent.equals("head") || parent.equals("head-child")) {
                name = "head-child";
            } else if (parent.equals("body") || parent.equals("outline")) {
                if (!name.equals("outline")) {
                    fail(OpmlParseException.Reason.STRUCTURE);
                }
                if (++outlineDepth > SecurityLimits.MAX_OPML_DEPTH
                        || ++outlineCount > SecurityLimits.MAX_OPML_OUTLINES) {
                    fail(OpmlParseException.Reason.OUTLINE_LIMIT);
                }
                String candidate = attribute(attrs, "xmlUrl");
                if (candidate != null) {
                    if (++candidateCount > SecurityLimits.MAX_OPML_CANDIDATES) {
                        fail(OpmlParseException.Reason.CANDIDATE_LIMIT);
                    }
                    accept(candidate, attrs);
                }
            } else {
                fail(OpmlParseException.Reason.STRUCTURE);
            }
            stack.push(name);
        }

        private void accept(String candidate, Attributes attrs) {
            try {
                CanonicalHttpsUrl url = CanonicalHttpsUrl.parse(candidate);
                String label = attribute(attrs, "title");
                if (label == null || label.trim().isEmpty()) {
                    label = attribute(attrs, "text");
                }
                if (label == null || label.trim().isEmpty()) {
                    label = url.getHost();
                } else {
                    label = label.trim();
                }
                if (label.length() > SecurityLimits.MAX_OPML_LABEL_CHARS) {
                    rejectedCount++;
                } else if (seen.add(url)) {
                    feeds.add(new OpmlDocument.Feed(url, label));
                } else {
                    duplicateCount++;
                }
            } catch (NetworkPolicyException exception) {
                rejectedCount++;
            }
        }

        private static String attribute(Attributes attrs, String name) {
            for (int index = 0; index < attrs.getLength(); index++) {
                if (attrs.getURI(index).isEmpty()
                        && name.equals(attrs.getLocalName(index).isEmpty()
                                ? attrs.getQName(index) : attrs.getLocalName(index))) {
                    return attrs.getValue(index);
                }
            }
            return null;
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            if (stack.pop().equals("outline")) {
                outlineDepth--;
            }
        }

        @Override
        public void startDTD(String name, String publicId, String systemId)
                throws SAXException {
            fail(OpmlParseException.Reason.DTD_FORBIDDEN);
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId)
                throws SAXException {
            fail(OpmlParseException.Reason.DTD_FORBIDDEN);
            return null;
        }

        @Override
        public InputSource resolveEntity(String name, String publicId,
                String baseUri, String systemId) throws SAXException {
            fail(OpmlParseException.Reason.DTD_FORBIDDEN);
            return null;
        }

        private static void fail(OpmlParseException.Reason reason) throws SAXException {
            throw new SAXException(policy(reason));
        }

        private OpmlDocument document() throws OpmlParseException {
            if (!bodySeen || !stack.isEmpty()) {
                throw policy(OpmlParseException.Reason.STRUCTURE);
            }
            return new OpmlDocument(feeds, rejectedCount, duplicateCount);
        }
    }
}
