package com.example.android.sampletvinput;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager Worker that periodically syncs channel and program data.
 * Ensures the EPG stays fresh even without user interaction.
 *
 * Scheduled from BootReceiver (after a reboot or an update) and after settings changes.
 * Uses ExistingPeriodicWorkPolicy.UPDATE — safe to call repeatedly, and picks up a changed interval.
 */
public class ChannelSyncWorker extends Worker {
    private static final String TAG = "ChannelSyncWorker";
    private static final String WORK_NAME = "live_tv_channel_sync";
    private static final String ONE_TIME_WORK_NAME = "live_tv_channel_sync_now";

    /** Default interval, in hours, between periodic channel syncs. */
    public static final int SYNC_INTERVAL_HOURS = 12;

    public ChannelSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.i(TAG, "Periodic channel sync starting");
            GuideSync.syncChannels(getApplicationContext());
            Log.i(TAG, "Periodic channel sync complete");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Periodic channel sync failed", e);
            return Result.retry();
        }
    }

    /**
     * Runs a channel sync as soon as the network allows. Broadcast receivers use this rather
     * than syncing inline: downloading the guide takes longer than a receiver may run.
     *
     * @param context Application context
     */
    public static void syncNow(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ChannelSyncWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
        );

        Log.i(TAG, "Queued one-time sync");
    }

    /**
     * Schedules periodic channel sync. Safe to call multiple times —
     * UPDATE policy keeps a single periodic job and applies the current interval.
     *
     * @param context Application context
     * @param intervalHours Sync interval in hours
     */
    public static void schedule(Context context, int intervalHours) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ChannelSyncWorker.class, intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );

        Log.i(TAG, "Scheduled periodic sync every " + intervalHours + " hours");
    }
}
