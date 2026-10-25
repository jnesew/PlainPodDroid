package io.github.jnesew.plainpoddroid;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;

import io.github.jnesew.plainpoddroid.catalog.EpisodeCatalogDatabase;
import io.github.jnesew.plainpoddroid.core.artwork.ThumbnailDiskCache;
import io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** Explicit opt-in screenshot fixture, compiled only into the test APK. No network. */
public final class ScreenshotFixtureRunner extends Instrumentation {
    private static final String MARKER = "plainpod-original-demo-v1";
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (!"true".equals(arguments.getString("seedDemo"))
                    || !context.getPackageName().equals("io.github.jnesew.plainpoddroid.debug")
                    || (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
                    || !(android.os.Build.HARDWARE.equals("ranchu")
                         || android.os.Build.HARDWARE.equals("goldfish"))) {
                throw new IllegalStateException("Use an explicitly selected disposable debug emulator.");
            }
            var preferences = context.getSharedPreferences("screenshot_fixture", Context.MODE_PRIVATE);
            boolean seeded = MARKER.equals(preferences.getString("owner", ""));
            try (EpisodeCatalogDatabase catalog = new EpisodeCatalogDatabase(context)) {
                SQLiteDatabase db = catalog.getWritableDatabase();
                try (var count = db.rawQuery("SELECT COUNT(*) FROM subscriptions", null)) {
                    count.moveToFirst();
                    if (!seeded && count.getLong(0) != 0) {
                        throw new IllegalStateException("Refusing to replace an existing library.");
                    }
                    if (seeded && count.getLong(0) != 6) {
                        throw new IllegalStateException("The demo library changed; use a fresh emulator.");
                    }
                }
                try (var urls = db.rawQuery("SELECT subscription_url FROM subscriptions", null)) {
                    while (urls.moveToNext()) {
                        if (!urls.getString(0).matches("https://feeds[.]example/demo-[1-6][.]xml")) {
                            throw new IllegalStateException("Refusing a library with non-demo subscriptions.");
                        }
                    }
                }
                if (!new PlaybackPrivacySettings(context).setNeverStreamAudioEnabled(true)) {
                    throw new IllegalStateException("Could not persist Offline mode for the fixture.");
                }
                if (!seeded) {
                    db.beginTransaction();
                    try {
                        seed(context, db);
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    if (!preferences.edit().putString("owner", MARKER).commit()) {
                        throw new IllegalStateException("Could not persist fixture ownership.");
                    }
                }
            }
            String theme = arguments.getString("theme", "light");
            if (!new DisplayThemeSettings(context).setTheme(
                    theme.equals("dark") ? DisplayTheme.DARK : DisplayTheme.LIGHT)) {
                throw new IllegalStateException("Could not persist demo theme.");
            }
            exportIcon(context);
            result.putString("stream", "Original synthetic library ready; networking is disabled.\n");
            finish(0, result);
        } catch (Exception error) {
            result.putString("stream", "Screenshot setup failed: " + error.getMessage() + "\n");
            finish(1, result);
        }
    }

    private static void seed(Context context, SQLiteDatabase db) throws Exception {
        String[] shows = {"Small Wonders", "Beyond the Horizon", "The Quiet Workshop",
                "Field Notes", "Everyday Science", "The Listening Room"};
        String[] authors = {"Mara & Ellis", "Open Sky Studio", "Ada Morgan",
                "Northbound Audio", "The Curious Collective", "Rowan Blake"};
        String[][] titles = {
                {"The hidden life of city trees", "A pocket guide to paying attention", "Why birds sing at dawn"},
                {"Finding our way by the stars", "The ocean beneath the ice", "A map of the night sky"},
                {"Making room for a slower day", "The joy of fixing things", "Objects with a story"},
                {"Along the old coastal path", "A morning in the forest", "The sounds between the rain"},
                {"Why a good question matters", "The surprising science of sleep", "A little light on colour"},
                {"An album for a rainy afternoon", "Listening beyond the melody", "The shape of a song"}};
        int[] colors = {0xff167c70, 0xff5144a5, 0xffaa6338, 0xff3f6a50, 0xff316787, 0xff934f71};
        long now = 1792843200000L; // Fixed demonstration dates, never a publisher feed.
        ThumbnailDiskCache cache = new ThumbnailDiskCache(context.getCacheDir().toPath().resolve("artwork-v1"));
        for (int show = 0; show < shows.length; show++) {
            long id = show + 1;
            String art = "https://artwork.example/demo-" + id + ".png";
            ContentValues values = new ContentValues();
            for (String key : new String[]{"notes", "website_url", "categories"}) values.put(key, "");
            values.put("id", id); values.put("title", shows[show]); values.put("author", authors[show]);
            values.put("artwork_url", art);
            values.put("subscription_url", "https://feeds.example/demo-" + id + ".xml");
            values.put("final_feed_url", "https://feeds.example/demo-" + id + ".xml");
            values.put("created_at", now); values.put("updated_at", now); values.put("seen_generation", 1);
            db.insertOrThrow("subscriptions", null, values);
            for (int size : new int[]{128, 512}) {
                cache.put(ThumbnailDiskCache.key(art, size), cover(shows[show], colors[show], show, size),
                        System.currentTimeMillis());
            }
            for (int episode = 0; episode < 3; episode++) {
                String mediaId = "episode-" + String.format(java.util.Locale.ROOT, "%064x", id * 10 + episode);
                ContentValues row = new ContentValues();
                row.put("media_id", mediaId); row.put("subscription_id", id);
                row.put("audio_url", "https://audio.example/demo-" + id + "-" + episode + ".mp3");
                row.put("mime_type", "audio/mpeg"); row.put("declared_length", -1);
                row.put("title", titles[show][episode]); row.put("author", authors[show]);
                row.put("notes", "A fresh perspective on the familiar.\n\n"
                        + "Join " + authors[show] + " for an unhurried conversation about the small details "
                        + "that make the world interesting. This episode explores " + titles[show][episode].toLowerCase(java.util.Locale.ROOT)
                        + ", with stories, practical ideas and time to reflect.\n\n"
                        + "00:00 A place to begin\n08:30 Looking a little closer\n21:15 Something to take with you\n\n"
                        + "An original fictional podcast created for PlainPodDroid screenshots.");
                row.put("website_url", ""); row.put("chapter_url", ""); row.put("categories", "");
                row.put("published", new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
                        .format(new java.util.Date(now - (show * 3L + episode) * 86400000L)));
                row.put("published_at", now - (show * 3L + episode) * 86400000L);
                row.put("duration_ms", (31 * 60 + 45 + show * 40 + episode * 90) * 1000L);
                row.put("source_order", episode); row.put("last_seen_generation", 1); row.put("first_seen_generation", 1);
                row.put("created_at", now); row.put("updated_at", now);
                row.put("favorite", episode == 0 && show < 3 ? 1 : 0);
                db.insertOrThrow("episodes", null, row);
                if (episode == 0 && show < 4) {
                    ContentValues queue = new ContentValues();
                    queue.put("media_id", mediaId); queue.put("position", show); queue.put("enqueued_at", now + show);
                    db.insertOrThrow("episode_queue", null, queue);
                }
            }
        }
    }

    private static byte[] cover(String title, int color, int index, int size) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(color);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(size * .012f);
        paint.setColor(0x66ffffff);
        for (int ring = 1; ring < 5; ring++) {
            canvas.drawCircle(size * (.68f - index * .035f), size * .31f, size * .11f * ring, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33ffffff); canvas.drawRect(0, size * .66f, size, size, paint);
        paint.setColor(Color.WHITE); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(size * .12f);
        String[] words = title.split(" ");
        for (int i = 0; i < words.length; i++) {
            canvas.drawText(words[i], size * .08f, size * (.64f + i * .13f), paint);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle();
        return bytes.toByteArray();
    }

    private static void exportIcon(Context context) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        var icon = context.getDrawable(R.mipmap.ic_launcher);
        icon.setBounds(0, 0, 512, 512); icon.draw(canvas);
        File output = new File(context.getFilesDir(), "screenshot-icon.png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        bitmap.recycle();
    }
}
