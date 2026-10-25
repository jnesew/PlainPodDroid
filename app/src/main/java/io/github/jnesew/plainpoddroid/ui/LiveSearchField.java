package io.github.jnesew.plainpoddroid.ui;

import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** Keeps typing/focus independent of results, and removes delayed work on close. */
public final class LiveSearchField implements AutoCloseable {
    private final EditText field;
    private final Handler handler;
    private final Runnable apply;
    private final TextWatcher watcher;

    public LiveSearchField(EditText field, Handler handler, Runnable apply) {
        this(field,handler,apply,()->{});
    }

    public LiveSearchField(EditText field, Handler handler, Runnable apply, Runnable changed) {
        this.field = field;
        this.handler = handler;
        this.apply = apply;
        watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable text) {
                changed.run();
                handler.removeCallbacks(apply);
                if (text.length() == 0) apply.run();
                else handler.postDelayed(apply, 250L);
            }
        };
        field.addTextChangedListener(watcher);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            flush();
            return true;
        });
    }

    public void flush() { handler.removeCallbacks(apply); apply.run(); }

    @Override public void close() {
        handler.removeCallbacks(apply);
        field.removeTextChangedListener(watcher);
        field.setOnEditorActionListener(null);
    }
}
