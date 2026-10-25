package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

/** Local information only; this screen performs no network work. */
public final class AboutActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DisplayThemeSettings.themedContext(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        TextView version = findViewById(R.id.about_version);
        version.setText(getString(R.string.about_version_format,
                BuildConfig.VERSION_NAME));
        Button close = findViewById(R.id.about_close);
        close.setOnClickListener(ignored -> finish());
        findViewById(R.id.about_app_license).setOnClickListener(ignored -> showLicenses(false));
        findViewById(R.id.about_third_party_licenses).setOnClickListener(ignored -> showLicenses(true));
    }

    private void showLicenses(boolean thirdParty) {
        try {
            StringBuilder text = new StringBuilder(readAsset(thirdParty
                    ? "legal/THIRD_PARTY_NOTICES.md" : "legal/LICENSE"));
            if (thirdParty) {
                String[] files = getAssets().list("legal/licenses");
                if (files != null) {
                    java.util.Arrays.sort(files);
                    for (String file : files) text.append("\n\n").append(file).append("\n\n")
                            .append(readAsset("legal/licenses/" + file));
                }
            }
            TextView body = new TextView(this);
            body.setText(text.toString().replaceAll("(?m)^#{1,6} ", ""));
            body.setTextIsSelectable(true);
            body.setPadding(24, 16, 24, 16);
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.addView(body);
            new android.app.AlertDialog.Builder(this).setTitle(thirdParty
                    ? R.string.about_third_party_licenses : R.string.about_app_license)
                    .setView(scroll).setPositiveButton(android.R.string.ok, null).show();
        } catch (java.io.IOException exception) {
            android.widget.Toast.makeText(this, R.string.about_license_error,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private String readAsset(String path) throws java.io.IOException {
        try (var reader = new java.io.InputStreamReader(getAssets().open(path), java.nio.charset.StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (text.length() > 512 * 1024 - read) throw new java.io.IOException("License asset too large.");
                text.append(buffer, 0, read);
            }
            return text.toString();
        }
    }
}
