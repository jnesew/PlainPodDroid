package io.github.jnesew.plainpoddroid.ui;

import android.test.InstrumentationTestCase;
import android.util.SparseArray;
import android.widget.EditText;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;

@SuppressWarnings("deprecation")
public final class FeedLoginEditorTest extends InstrumentationTestCase {
    public void testSavedLoginKeepsPasswordOutOfFieldsAndViewState() throws Throwable {
        runTestOnUiThread(() -> {
            try {
                var owner = CanonicalHttpsUrl.parse("https://feed.example/rss");
                var login = new BasicFeedLogin(owner, "user", "test-password", null);
                var editor = new FeedLoginEditor(getInstrumentation().getTargetContext(), () -> { });
                editor.setExisting(login);
                assertEquals("", ((EditText) editor.findViewById(R.id.login_password)).getText().toString());
                assertTrue(login.sameLogin(editor.read(owner)));
                SparseArray<android.os.Parcelable> state = new SparseArray<>(); editor.saveHierarchyState(state);
                var replacement = new FeedLoginEditor(getInstrumentation().getTargetContext(), () -> { });
                replacement.restoreHierarchyState(state);
                assertNull(replacement.read(owner));
                assertEquals("", ((EditText) replacement.findViewById(R.id.login_username)).getText().toString());
                editor.clear(); assertNull(editor.read(owner));
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
}
