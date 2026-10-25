package io.github.jnesew.plainpoddroid.core.security;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ManifestPermissionTest {
    private static final List<String> REQUIRED_PERMISSIONS = List.of(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.RECEIVE_BOOT_COMPLETED");

    @Test
    public void manifestContainsOnlyTheFiveRequiredPermissions() throws Exception {
        String manifest = SourceFileReader.readString(manifestPath(), StandardCharsets.UTF_8);

        assertEquals(REQUIRED_PERMISSIONS.size(), count(manifest, "<uses-permission "));
        for (String permission : REQUIRED_PERMISSIONS) {
            assertTrue(manifest.contains("android:name=\"" + permission + "\""));
        }
        assertFalse(manifest.contains("android.permission.POST_NOTIFICATIONS"));
        assertFalse(manifest.contains("android.permission.SCHEDULE_EXACT_ALARM"));
        assertFalse(manifest.contains("android.permission.READ_MEDIA_AUDIO"));
        assertFalse(manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"));
    }

    @Test public void preparationHasOnlyANonExportedJobAndAnOptInProtectedSystemReceiver() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var xml = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                SourceFileReader.readString(manifestPath(), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)));
        String android = "http://schemas.android.com/apk/res/android";
        var receivers = xml.getElementsByTagName("receiver");
        assertEquals(1, receivers.getLength());
        var receiver = (org.w3c.dom.Element) receivers.item(0);
        assertEquals(".PreparationScheduleReceiver", receiver.getAttributeNS(android, "name"));
        assertEquals("false", receiver.getAttributeNS(android, "exported"));
        assertEquals("false", receiver.getAttributeNS(android, "enabled"));
        var actions = receiver.getElementsByTagName("action");
        var names = new java.util.HashSet<String>();
        for (int i = 0; i < actions.getLength(); i++)
            names.add(((org.w3c.dom.Element) actions.item(i)).getAttributeNS(android, "name"));
        assertEquals(java.util.Set.of("android.intent.action.BOOT_COMPLETED", "android.intent.action.TIME_SET",
                "android.intent.action.TIMEZONE_CHANGED", "android.intent.action.MY_PACKAGE_REPLACED"), names);
        var services = xml.getElementsByTagName("service");
        int preparation = 0;
        for (int i = 0; i < services.getLength(); i++) {
            var service = (org.w3c.dom.Element) services.item(i);
            if (!".PreparationJobService".equals(service.getAttributeNS(android, "name"))) continue;
            preparation++;
            assertEquals("false", service.getAttributeNS(android, "exported"));
            assertEquals("android.permission.BIND_JOB_SERVICE", service.getAttributeNS(android, "permission"));
        }
        assertEquals(1, preparation);
    }

    private static Path manifestPath() throws IOException {
        Path rootRelative = Path.of("app", "src", "main", "AndroidManifest.xml");
        if (Files.isRegularFile(rootRelative)) {
            return rootRelative;
        }
        Path appRelative = Path.of("src", "main", "AndroidManifest.xml");
        if (Files.isRegularFile(appRelative)) {
            return appRelative;
        }
        throw new IOException("Android manifest was unavailable to the test.");
    }

    private static int count(String value, String expected) {
        int result = 0;
        int offset = 0;
        while (true) {
            int match = value.indexOf(expected, offset);
            if (match < 0) {
                return result;
            }
            result++;
            offset = match + expected.length();
        }
    }
}
