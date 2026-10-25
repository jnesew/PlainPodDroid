package io.github.jnesew.plainpoddroid;

import android.content.SharedPreferences;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DownloadRetentionSettingsTest {
    private static final String KEY = "auto_delete_completed";

    @Test
    public void missingOrCorruptPreferenceDefaultsOff() {
        FakePreferences fake = new FakePreferences();
        assertFalse(new DownloadRetentionSettings(fake.preferences()).isAutoDeleteCompleted());
        fake.memory.put(KEY, "invalid");
        assertFalse(new DownloadRetentionSettings(fake.preferences()).isAutoDeleteCompleted());
    }

    @Test
    public void enablingWaitsForSuccessfulDiskCommit() {
        FakePreferences fake = new FakePreferences();
        DownloadRetentionSettings settings = new DownloadRetentionSettings(fake.preferences());
        fake.duringCommit = () -> assertFalse(settings.isAutoDeleteCompleted());
        assertTrue(settings.setAutoDeleteCompleted(true));
        assertTrue(settings.isAutoDeleteCompleted());
        fake.duringCommit = () -> {};
        assertTrue(new DownloadRetentionSettings(fake.preferences()).isAutoDeleteCompleted());
    }

    @Test
    public void failedEnablingAndFailedRollbackStayOff() {
        FakePreferences fake = new FakePreferences();
        DownloadRetentionSettings settings = new DownloadRetentionSettings(fake.preferences());
        fake.saveSucceeds = false;
        fake.duringCommit = () -> assertFalse(settings.isAutoDeleteCompleted());
        assertFalse(settings.setAutoDeleteCompleted(true));
        assertFalse(settings.isAutoDeleteCompleted());
        assertFalse((Boolean) fake.memory.get(KEY));
        assertFalse(fake.disk.containsKey(KEY));
    }

    @Test
    public void disablingStopsCleanupBeforeAFailedSave() {
        FakePreferences fake = new FakePreferences();
        fake.memory.put(KEY, true);
        fake.disk.put(KEY, true);
        DownloadRetentionSettings settings = new DownloadRetentionSettings(fake.preferences());
        assertTrue(settings.isAutoDeleteCompleted());
        fake.saveSucceeds = false;
        fake.duringCommit = () -> assertFalse(settings.isAutoDeleteCompleted());
        assertFalse(settings.setAutoDeleteCompleted(false));
        assertFalse(settings.isAutoDeleteCompleted());
        assertTrue((Boolean) fake.disk.get(KEY)); // Restart still needs a successful save.
    }

    @Test
    public void commitExceptionCannotEnableCleanup() {
        FakePreferences fake = new FakePreferences();
        DownloadRetentionSettings settings = new DownloadRetentionSettings(fake.preferences());
        fake.throwOnCommit = true;
        assertFalse(settings.setAutoDeleteCompleted(true));
        assertFalse(settings.isAutoDeleteCompleted());
    }

    /** Models Android's memory-before-disk commit behavior, including failed writes. */
    private static final class FakePreferences {
        private final Map<String, Object> memory = new HashMap<>();
        private final Map<String, Object> disk = new HashMap<>();
        private boolean saveSucceeds = true;
        private boolean throwOnCommit;
        private Runnable duringCommit = () -> {};

        SharedPreferences preferences() {
            return (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getBoolean")) {
                            return (Boolean) memory.getOrDefault(args[0], args[1]);
                        }
                        if (method.getName().equals("edit")) return editor();
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private SharedPreferences.Editor editor() {
            Map<String, Object> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("putBoolean")) {
                            pending.put((String) args[0], args[1]);
                            return proxy;
                        }
                        if (method.getName().equals("commit")) {
                            memory.putAll(pending);
                            duringCommit.run();
                            if (throwOnCommit) throw new IllegalStateException("Injected failure");
                            if (saveSucceeds) disk.putAll(pending);
                            return saveSucceeds;
                        }
                        if (method.getName().equals("apply")) {
                            memory.putAll(pending);
                            if (saveSucceeds) disk.putAll(pending);
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
