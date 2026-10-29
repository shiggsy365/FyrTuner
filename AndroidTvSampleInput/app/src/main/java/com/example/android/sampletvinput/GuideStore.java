package com.example.android.sampletvinput;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FyrTuner's own copy of the guide: the lineup and programmes written by GuideSync and read by
 * GuideData, plus logo files in files/logo_cache. Listeners hear when a sync has finished.
 */
public final class GuideStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "guide.db";
    private static final int DB_VERSION = 3;

    public static final String CHANNELS = "channels";
    public static final String PROGRAMS = "programs";

    private static GuideStore sInstance;
    private static final List<Runnable> sListeners = new CopyOnWriteArrayList<>();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private GuideStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static synchronized GuideStore get(Context context) {
        if (sInstance == null) sInstance = new GuideStore(context);
        return sInstance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + CHANNELS + " ("
                + "stream_id INTEGER PRIMARY KEY, "
                + "number INTEGER NOT NULL, "
                + "name TEXT NOT NULL, "
                + "group_id TEXT NOT NULL, "
                + "logo_key TEXT, "
                // The channel's streams in playback order, and their names, as JSON arrays.
                + "streams TEXT NOT NULL, "
                + "stream_names TEXT NOT NULL, "
                // Catch-up days for each stream (0 for none), as a JSON array like streams.
                + "archive TEXT, "
                // The guide link and offset the stored programmes came from (see GuideSync).
                + "guide_key TEXT)");
        db.execSQL("CREATE TABLE " + PROGRAMS + " ("
                + "stream_id INTEGER NOT NULL, "
                + "start_ms INTEGER NOT NULL, "
                + "end_ms INTEGER NOT NULL, "
                + "title TEXT, "
                + "description TEXT)");
        db.execSQL("CREATE INDEX programs_by_channel ON " + PROGRAMS + " (stream_id, start_ms)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 2) {
            // Keep the guide until the next sync fills these in.
            db.execSQL("ALTER TABLE " + CHANNELS + " ADD COLUMN archive TEXT");
            db.execSQL("ALTER TABLE " + CHANNELS + " ADD COLUMN guide_key TEXT");
            return;
        }
        // Everything here is rebuilt by the next sync.
        db.execSQL("DROP TABLE IF EXISTS " + CHANNELS);
        db.execSQL("DROP TABLE IF EXISTS " + PROGRAMS);
        onCreate(db);
    }

    /** Where a channel's logo is cached. */
    public static File logoFile(Context context, int streamId) {
        return new File(new File(context.getFilesDir(), "logo_cache"), streamId + ".png");
    }

    public static void addListener(Runnable listener) {
        sListeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        sListeners.remove(listener);
    }

    /** Tells listeners, on the main thread, that the guide changed. */
    static void notifyChanged() {
        sMainHandler.post(() -> {
            for (Runnable listener : sListeners) listener.run();
        });
    }
}
