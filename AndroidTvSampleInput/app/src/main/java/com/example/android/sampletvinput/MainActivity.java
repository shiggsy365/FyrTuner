package com.example.android.sampletvinput;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV settings screen: the Xtream account and a sync button. Groups, channels and guides are
 * managed from the web page whose address this screen shows.
 */
public class MainActivity extends Activity {

    private EditText mServer;
    private EditText mUsername;
    private EditText mPassword;
    private CheckBox mUseHls;
    private TextView mStatus;
    private Button mSyncButton;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        WebServerService.start(this);

        mServer = findViewById(R.id.server);
        mUsername = findViewById(R.id.username);
        mPassword = findViewById(R.id.password);
        mUseHls = findViewById(R.id.use_hls);
        mStatus = findViewById(R.id.status);
        mSyncButton = findViewById(R.id.save_sync);

        SharedPreferences prefs = XtreamClient.prefs(this);
        mServer.setText(prefs.getString(XtreamClient.PREF_SERVER, ""));
        mUsername.setText(prefs.getString(XtreamClient.PREF_USERNAME, ""));
        mPassword.setText(prefs.getString(XtreamClient.PREF_PASSWORD, ""));
        mUseHls.setChecked(prefs.getBoolean(XtreamClient.PREF_USE_HLS, false));
        mSyncButton.setOnClickListener(v -> saveAndSync());

        long lastSync = prefs.getLong(XtreamClient.PREF_LAST_SYNC, 0);
        mStatus.setText(lastSync == 0 ? "Not synced yet"
                : "Last synced " + DateUtils.getRelativeTimeSpanString(lastSync));
    }

    @Override
    protected void onResume() {
        super.onResume();
        String url = WebServerService.pageUrl();
        ((TextView) findViewById(R.id.web_url)).setText(
                url != null ? url : "No network connection");
    }

    private void saveAndSync() {
        String server = mServer.getText().toString().trim();
        String username = mUsername.getText().toString().trim();
        String password = mPassword.getText().toString().trim();
        if (TextUtils.isEmpty(server) || TextUtils.isEmpty(username)
                || TextUtils.isEmpty(password)) {
            mStatus.setText("Enter the server, username and password");
            return;
        }
        XtreamClient.prefs(this).edit()
                .putString(XtreamClient.PREF_SERVER, server)
                .putString(XtreamClient.PREF_USERNAME, username)
                .putString(XtreamClient.PREF_PASSWORD, password)
                .putBoolean(XtreamClient.PREF_USE_HLS, mUseHls.isChecked())
                .commit();

        mSyncButton.setEnabled(false);
        mStatus.setText("Syncing channels and guide. This can take a few minutes…");
        mExecutor.execute(() -> {
            String error = new XtreamClient(server, username, password, mUseHls.isChecked())
                    .checkAccount();
            boolean ok = error == null && GuideSync.syncChannels(getApplicationContext());
            if (error == null) {
                ChannelSyncWorker.schedule(getApplicationContext(),
                        ChannelSyncWorker.SYNC_INTERVAL_HOURS);
            }
            final String message = error != null ? error
                    : ok ? "Sync complete" : GuideSync.sSyncStatus;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                mSyncButton.setEnabled(true);
                mStatus.setText(message);
            });
        });
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }
}
