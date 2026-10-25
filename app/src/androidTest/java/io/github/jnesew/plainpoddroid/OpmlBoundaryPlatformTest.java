package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.core.opml.OpmlDocument;
import io.github.jnesew.plainpoddroid.core.opml.OpmlImportPlan;
import io.github.jnesew.plainpoddroid.core.opml.OpmlMigrationStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real ContentResolver/file boundary with a minimal activity-owned view fixture. */
@SuppressWarnings("deprecation")
public final class OpmlBoundaryPlatformTest extends InstrumentationTestCase {
    private static final class PanelActivity extends Activity {
        private final Map<Integer, View> views = new HashMap<>();
        PanelActivity(Context context) {
            attachBaseContext(context);
            for (int id : new int[] {R.id.import_opml, R.id.confirm_opml, R.id.export_opml,
                    R.id.settings_import_opml, R.id.opml_details}) views.put(id, new Button(context));
            for (int id : new int[] {R.id.opml_status, R.id.opml_export_status, R.id.opml_refresh_notice})
                views.put(id, new TextView(context));
        }
        @Override @SuppressWarnings("unchecked") public <T extends View> T findViewById(int id) {
            return (T) views.get(id);
        }
    }

    public void testFileUriPickerResultCannotOverwriteAnAppPrivateFile() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        File victim = File.createTempFile("opml-boundary-", ".txt", target.getCacheDir());
        byte[] original = "retained synthetic data".getBytes(StandardCharsets.UTF_8);
        Files.write(victim.toPath(), original);
        OpmlMigrationPanel[] panel = new OpmlMigrationPanel[1];
        OpmlMigrationStore unused = new OpmlMigrationStore() {
            public OpmlImportPlan preview(OpmlDocument input) { throw new AssertionError("Unexpected import"); }
            public OpmlImportPlan importConfirmed(OpmlDocument input) { throw new AssertionError("Unexpected import"); }
            public List<OpmlDocument.Feed> listForExport() { throw new AssertionError("Unexpected catalog read"); }
        };
        try {
            getInstrumentation().runOnMainSync(() -> {
                PanelActivity activity = new PanelActivity(new ContextThemeWrapper(target, R.style.Theme_PlainPodDroid));
                panel[0] = new OpmlMigrationPanel(activity, unused,
                        Runnable::run, new Handler(Looper.getMainLooper()), () -> {}, () -> {});
                try {
                    var pending = OpmlMigrationPanel.class.getDeclaredField("pendingOpmlExport");
                    pending.setAccessible(true);
                    pending.set(panel[0], "<opml><body/></opml>".getBytes(StandardCharsets.UTF_8));
                } catch (ReflectiveOperationException unavailable) { throw new AssertionError(unavailable); }
                panel[0].onActivityResult(7022, Activity.RESULT_OK,
                        new Intent().setData(Uri.fromFile(victim)));
            });
            getInstrumentation().waitForIdleSync();
            assertTrue("File URI export overwrote private state",
                    java.util.Arrays.equals(original, Files.readAllBytes(victim.toPath())));
            assertFalse(panel[0].isBusy());
        } finally {
            getInstrumentation().runOnMainSync(() -> { if (panel[0] != null) panel[0].close(); });
            Files.deleteIfExists(victim.toPath());
            getInstrumentation().waitForIdleSync();
        }
    }
}
