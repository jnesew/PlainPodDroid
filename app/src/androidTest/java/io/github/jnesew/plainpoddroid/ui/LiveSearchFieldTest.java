package io.github.jnesew.plainpoddroid.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public final class LiveSearchFieldTest extends InstrumentationTestCase {
    private EditText field;
    private LiveSearchField binding;
    private final List<String> queries=new ArrayList<>();
    @Override protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().runOnMainSync(()->{
            field=new EditText(getInstrumentation().getTargetContext());
            binding=new LiveSearchField(field,new Handler(Looper.getMainLooper()),
                    ()->queries.add(field.getText().toString()));
        });
    }
    @Override protected void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(binding::close);super.tearDown();
    }
    public void testTypingCoalescesAndClearImmediatelyReplacesPendingSearch() {
        getInstrumentation().runOnMainSync(()->{
            field.setText("a");field.setText("ab");field.setText("abc");assertTrue(queries.isEmpty());
        });
        SystemClock.sleep(350L);
        getInstrumentation().runOnMainSync(()->{
            assertEquals(List.of("abc"),queries);
            field.setText("pending");field.setText("");assertEquals(List.of("abc",""),queries);
        });
        SystemClock.sleep(350L);
        getInstrumentation().runOnMainSync(()->assertEquals(List.of("abc",""),queries));
    }
    public void testKeyboardSearchFlushesAndCloseDropsDelayedTyping() {
        getInstrumentation().runOnMainSync(()->{
            field.setText("immediate");field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertEquals(List.of("immediate"),queries);
            field.setText("closed");binding.close();
        });
        SystemClock.sleep(350L);
        getInstrumentation().runOnMainSync(()->assertEquals(List.of("immediate"),queries));
    }
}
