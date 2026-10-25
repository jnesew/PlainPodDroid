package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.auth.BasicFeedLogin;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;

/** Memory-only input shared by Add podcast and Login. Passwords never enter saved view state. */
// Created only in Java: the required edit callback cannot be supplied by an XML inflater.
@android.annotation.SuppressLint("ViewConstructor")
public final class FeedLoginEditor extends LinearLayout {
    private final Switch enabled;
    private final LinearLayout fields;
    private final EditText username, password, audio;
    private BasicFeedLogin previous;
    public FeedLoginEditor(Context context, Runnable changed) {
        super(context); setOrientation(VERTICAL); setSaveEnabled(false); setSaveFromParentEnabled(false);
        enabled = new Switch(context); enabled.setId(R.id.login_enabled); enabled.setText(R.string.login_required);
        enabled.setMinHeight(UiWidgets.dp(context, 48)); enabled.setSaveEnabled(false); addView(enabled);
        fields = new LinearLayout(context); fields.setOrientation(VERTICAL); addView(fields);
        username = field(R.id.login_username, R.string.login_username, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password = field(R.id.login_password, R.string.login_password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        audio = field(R.id.login_audio_scope, R.string.login_audio_scope, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        audio.setHint(R.string.login_audio_hint);
        TextView note = UiWidgets.text(context, context.getString(R.string.login_audio_help), 14, false); fields.addView(note);
        enabled.setOnCheckedChangeListener((button, checked) -> { fields.setVisibility(checked ? VISIBLE : GONE); changed.run(); });
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable value) { changed.run(); }
        };
        for (EditText input : new EditText[] {username, password, audio}) input.addTextChangedListener(watcher);
        fields.setVisibility(GONE);
    }
    private EditText field(int id, int label, int inputType) {
        TextView title = UiWidgets.text(getContext(), getContext().getString(label), 14, false);
        title.setLabelFor(id); fields.addView(title);
        EditText field = new EditText(getContext()); field.setId(id); field.setInputType(inputType);
        field.setSingleLine(true); field.setMinHeight(UiWidgets.dp(getContext(), 48));
        field.setSaveEnabled(false); field.setSaveFromParentEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(
                id == R.id.login_audio_scope ? 4096 : BasicFeedLogin.MAX_FIELD_CHARS)});
        fields.addView(field, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)); return field;
    }
    public void setExisting(BasicFeedLogin login) {
        previous = login; enabled.setChecked(login != null);
        username.setText(login == null ? "" : login.username()); password.setText("");
        password.setHint(login == null ? "" : getContext().getString(R.string.login_keep_password));
        audio.setText(login == null || login.audioScope() == null ? "" : login.audioScope().toString());
    }
    public BasicFeedLogin read(CanonicalHttpsUrl owner) {
        if (!enabled.isChecked()) return null;
        try {
            String address = audio.getText().toString().trim();
            CanonicalHttpsUrl scope = address.isEmpty() ? null : CanonicalHttpsUrl.parse(address);
            return previous == null ? new BasicFeedLogin(owner, username.getText().toString(), password.getText().toString(), scope)
                    : previous.updated(owner, username.getText().toString(), password.getText().toString(), scope);
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException(getContext().getString(R.string.login_invalid));
        }
    }
    public void setBusy(boolean busy) {
        enabled.setEnabled(!busy); for (EditText input : new EditText[] {username, password, audio}) input.setEnabled(!busy);
    }
    public void clear() { setExisting(null); }
}
