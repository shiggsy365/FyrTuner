package com.example.android.sampletvinput;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import org.json.JSONArray;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen playback for FyrTuner's guide. Up/Down (and channel keys) zap through the group
 * the channel was picked from; OK shows the banner with its buttons (streams, subtitles, start
 * over); Menu picks one of the channel's streams; Back hides the banner, then returns to the
 * guide.
 *
 * A channel can have several streams. If one fails, keeps buffering or stops advancing, the
 * next is tried; a fallback stream that plays moves to the top of the channel's list.
 *
 * With EXTRA_CATCHUP_START/END it plays a recording instead (catch-up), using the channel's
 * streams that have one. Left/Right and the rewind/fast-forward keys skip, play/pause pauses.
 */
public class PlayerActivity extends Activity {
    private static final String TAG = "PlayerActivity";
    public static final String EXTRA_GROUP_ID = "group_id";
    public static final String EXTRA_CHANNEL_ID = "channel_id";
    /** Wall-clock start and end of a programme to play from catch-up. */
    public static final String EXTRA_CATCHUP_START = "catchup_start";
    public static final String EXTRA_CATCHUP_END = "catchup_end";

    private static final String PREFS = "player";
    private static final String PREF_SUBTITLES = "subtitles";

    private static final long OVERLAY_MS = 5_000;
    private static final long OVERLAY_TICK_MS = 1_000;
    private static final long WATCHDOG_MS = 2_000;
    // A stream that takes longer than this to start counts as failed.
    private static final long START_TIMEOUT_MS = 15_000;
    // Buffering this long while playing counts as a hang.
    private static final long BUFFERING_TIMEOUT_MS = 10_000;
    // Playback position not moving for this long counts as a hang.
    private static final long STALL_TIMEOUT_MS = 8_000;
    // Catch-up skips: Left/Right, and the rewind/fast-forward keys.
    private static final long SKIP_MS = 30_000;
    private static final long SKIP_LONG_MS = 120_000;
    // Recordings stop this far short of now; the panel is still writing the rest.
    private static final long LIVE_EDGE_MS = 60_000;

    private StyledPlayerView mPlayerView;
    private ProgressBar mBuffering;
    private View mOverlay;
    private ImageView mOverlayLogo;
    private TextView mOverlayChannel;
    private TextView mOverlayBadge;
    private TextView mOverlayNow;
    private ProgressBar mOverlayProgress;
    private TextView mOverlayNext;
    private View mOverlayButtons;
    private TextView mBtnPlay;
    private TextView mBtnStartOver;
    private TextView mBtnLive;
    private TextView mBtnStreams;
    private TextView mBtnSubtitles;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private DateFormat mTimeFormat;
    private ExoPlayer mPlayer;
    private List<GuideData.Channel> mChannels = Collections.emptyList();
    private int mIndex;
    private SharedPreferences mPrefs;
    private boolean mSubtitles;

    // Current stream within the channel, and how many have been tried since the user tuned.
    private int mStreamIndex;
    private int mStreamsTried;
    private boolean mManualStream;
    private boolean mStarted;
    private long mStreamStartMs;
    private long mBufferingSinceMs;
    private long mLastPosition = -1;
    private long mLastProgressMs;
    // Each playStream call's number, so a slow account check for an earlier one is ignored.
    private int mPlaySerial;
    private int mPlayerSource;
    // Whether a stream was skipped since tuning because its account was full.
    private boolean mAccountBusy;

    // Catch-up: the programme being watched (0 when live), and the wall-clock time that
    // position 0 of the current recording URL stands for.
    private long mCatchupStart;
    private long mCatchupEnd;
    private String mCatchupTitle;
    private long mRecordingStart;

    private final Runnable mHideOverlay = () -> {
        mOverlay.setVisibility(View.GONE);
        mPlayerView.requestFocus();
    };
    private final Runnable mOverlayTick = new Runnable() {
        @Override
        public void run() {
            if (mOverlay.getVisibility() != View.VISIBLE) return;
            bindPrograms(mChannels.get(mIndex));
            mHandler.postDelayed(this, OVERLAY_TICK_MS);
        }
    };
    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            checkStream();
            mHandler.postDelayed(this, WATCHDOG_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.player_activity);
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);
        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mSubtitles = mPrefs.getBoolean(PREF_SUBTITLES, false);

        mPlayerView = findViewById(R.id.player_view);
        mPlayerView.setUseController(false);
        mBuffering = findViewById(R.id.buffering);
        mOverlay = findViewById(R.id.overlay);
        mOverlayLogo = findViewById(R.id.overlay_logo);
        mOverlayChannel = findViewById(R.id.overlay_channel);
        mOverlayBadge = findViewById(R.id.overlay_badge);
        mOverlayNow = findViewById(R.id.overlay_now);
        mOverlayProgress = findViewById(R.id.overlay_progress);
        mOverlayNext = findViewById(R.id.overlay_next);
        mOverlayButtons = findViewById(R.id.overlay_buttons);
        mBtnPlay = findViewById(R.id.btn_play);
        mBtnStartOver = findViewById(R.id.btn_start_over);
        mBtnLive = findViewById(R.id.btn_live);
        mBtnStreams = findViewById(R.id.btn_streams);
        mBtnSubtitles = findViewById(R.id.btn_subtitles);
        mBtnPlay.setOnClickListener(v -> togglePause());
        mBtnStartOver.setOnClickListener(v -> startOver());
        mBtnLive.setOnClickListener(v -> tune(mIndex));
        mBtnStreams.setOnClickListener(v -> showStreamMenu());
        mBtnSubtitles.setOnClickListener(v -> toggleSubtitles());

        GuideData.Group group = GuideData.lastGroup(
                getIntent().getStringExtra(EXTRA_GROUP_ID));
        if (group != null) mChannels = group.channels;
        long channelId = getIntent().getLongExtra(EXTRA_CHANNEL_ID, -1);
        for (int i = 0; i < mChannels.size(); i++) {
            if (mChannels.get(i).id == channelId) mIndex = i;
        }
        mCatchupStart = getIntent().getLongExtra(EXTRA_CATCHUP_START, 0);
        mCatchupEnd = getIntent().getLongExtra(EXTRA_CATCHUP_END, 0);
        if (mChannels.isEmpty()) {
            finish();
        } else if (mCatchupStart > 0) {
            mCatchupTitle = titleAt(mChannels.get(mIndex), mCatchupStart);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mChannels.isEmpty()) return;
        // Coming back to a recording resumes where it was; live just retunes.
        if (mCatchupStart > 0) {
            playCatchup(mRecordingStart > 0 ? mRecordingStart : mCatchupStart);
        } else {
            tune(mIndex);
        }
    }

    @Override
    protected void onStop() {
        // Release on stop, not pause: the account allows one stream at a time.
        if (mPlayer != null && mCatchupStart > 0) mRecordingStart = position();
        mHandler.removeCallbacks(mWatchdog);
        releasePlayer();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    // ---- Keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean overlay = mOverlay.getVisibility() == View.VISIBLE;
        // While a banner button has focus, Left/Right/OK belong to the buttons.
        boolean onButton = overlay && mOverlayButtons.hasFocus();
        if (overlay) scheduleHide();
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (onButton) break;
                if (down) {
                    if (mCatchupStart > 0) {
                        skip(key == KeyEvent.KEYCODE_DPAD_LEFT ? -SKIP_MS : SKIP_MS);
                    } else {
                        showOverlay(false);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (onButton) break;
                if (!down) showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (!down) showOverlay(true);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!down) showStreamMenu();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!down && mCatchupStart > 0) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down && mCatchupStart > 0) {
                    skip(key == KeyEvent.KEYCODE_MEDIA_REWIND ? -SKIP_LONG_MS : SKIP_LONG_MS);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (overlay) {
                    if (!down) mHideOverlay.run();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    private void zap(int direction) {
        if (mChannels.size() < 2 && mCatchupStart == 0) return;
        mIndex = (mIndex + direction + mChannels.size()) % mChannels.size();
        tune(mIndex);
    }

    // ---- Playback ----

    /** Tunes a channel live, starting with its first stream. */
    private void tune(int index) {
        mIndex = index;
        mCatchupStart = 0;
        mCatchupEnd = 0;
        mRecordingStart = 0;
        mStreamsTried = 0;
        mManualStream = false;
        mAccountBusy = false;
        playStream(0);
    }

    /** Plays the current catch-up programme from a wall-clock time, on a stream that has it. */
    private void playCatchup(long from) {
        GuideData.Channel channel = mChannels.get(mIndex);
        int first = -1;
        for (int i = 0; i < channel.streams.size(); i++) {
            if (channel.archiveDays.get(i) > 0) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            Toast.makeText(this, channel.name + " has no catch-up", Toast.LENGTH_LONG).show();
            tune(mIndex);
            return;
        }
        mRecordingStart = from;
        mStreamsTried = 0;
        mManualStream = false;
        mAccountBusy = false;
        playStream(mStreamIndex < channel.streams.size()
                && channel.archiveDays.get(mStreamIndex) > 0 ? mStreamIndex : first);
    }

    /** Starts the current live programme again from its beginning. */
    private void startOver() {
        if (mCatchupStart > 0) {
            seekTo(mCatchupStart);
            return;
        }
        GuideData.Channel channel = mChannels.get(mIndex);
        GuideData.Program p = channel.now;
        if (p == null || !channel.canCatchUp(p, System.currentTimeMillis())) return;
        mCatchupStart = p.startMs;
        mCatchupEnd = p.endMs;
        mCatchupTitle = p.title;
        playCatchup(p.startMs);
    }

    private void playStream(int streamIndex) {
        GuideData.Channel channel = mChannels.get(mIndex);
        mStreamIndex = streamIndex;
        mStreamsTried++;
        int streamId = channel.streams.get(streamIndex);
        String url;
        if (mCatchupStart > 0) {
            url = channel.archiveDays.get(streamIndex) > 0
                    ? Catchup.url(this, streamId, mRecordingStart, mCatchupEnd) : null;
        } else {
            url = Sources.streamUrl(this, streamId);
        }
        showOverlay(false);
        if (url == null) {
            // This stream's source has been removed, or it has no recording: try the next.
            mHandler.post(() -> failOver(mCatchupStart > 0 ? "has no catch-up" : "has no source"));
            return;
        }
        releasePlayer();
        mStarted = false;
        mBuffering.setVisibility(View.VISIBLE);
        // Don't take the account's only connection from someone watching on another TV.
        int source = Sources.sourceOf(streamId);
        int serial = ++mPlaySerial;
        mExecutor.execute(() -> {
            boolean busy = Connections.busy(getApplicationContext(), source);
            runOnUiThread(() -> {
                if (isDestroyed() || serial != mPlaySerial) return;
                if (busy) {
                    mAccountBusy = true;
                    failOver("is on an account in use on another TV");
                } else {
                    startPlayer(url, source);
                }
            });
        });
    }

    private void startPlayer(String url, int source) {
        mPlayerSource = source;
        Connections.using(source);
        mStreamStartMs = System.currentTimeMillis();
        mBufferingSinceMs = 0;
        mLastPosition = -1;
        mLastProgressMs = mStreamStartMs;

        // Panels often redirect streams to an edge server, sometimes across http/https.
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        mPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        applySubtitles();
        mPlayerView.setPlayer(mPlayer);
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                mBuffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_BUFFERING) {
                    if (mBufferingSinceMs == 0) mBufferingSinceMs = System.currentTimeMillis();
                } else {
                    mBufferingSinceMs = 0;
                }
                if (state == Player.STATE_READY && !mStarted) onStreamStarted();
                if (state == Player.STATE_ENDED && mCatchupStart > 0) {
                    Toast.makeText(PlayerActivity.this, "End of the recording",
                            Toast.LENGTH_SHORT).show();
                    tune(mIndex);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateButtons();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Stream error: " + error.getErrorCodeName());
                failOver("error");
            }
        });
        mPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        mPlayer.setPlayWhenReady(true);
        mPlayer.prepare();
        mHandler.removeCallbacks(mWatchdog);
        mHandler.postDelayed(mWatchdog, WATCHDOG_MS);
    }

    private void onStreamStarted() {
        mStarted = true;
        GuideData.Channel channel = mChannels.get(mIndex);
        // A fallback that works moves to the top of the channel for next time.
        if (mStreamIndex > 0 && !mManualStream && mCatchupStart == 0) {
            promoteStream(channel, mStreamIndex);
        }
    }

    /** Spots streams that never start, keep buffering, or stop advancing. */
    private void checkStream() {
        if (mPlayer == null) return;
        long now = System.currentTimeMillis();
        if (!mStarted) {
            if (now - mStreamStartMs > START_TIMEOUT_MS) failOver("didn't start");
            return;
        }
        if (mBufferingSinceMs > 0 && now - mBufferingSinceMs > BUFFERING_TIMEOUT_MS) {
            failOver("kept buffering");
            return;
        }
        if (mPlayer.getPlaybackState() == Player.STATE_READY && mPlayer.getPlayWhenReady()) {
            long position = mPlayer.getCurrentPosition();
            if (position != mLastPosition) {
                mLastPosition = position;
                mLastProgressMs = now;
            } else if (now - mLastProgressMs > STALL_TIMEOUT_MS) {
                failOver("stalled");
            }
        }
    }

    /** Moves to the channel's next stream, trying each once per tune. */
    private void failOver(String reason) {
        GuideData.Channel channel = mChannels.get(mIndex);
        Log.i(TAG, channel.name + " stream " + (mStreamIndex + 1) + " " + reason);
        int count = channel.streams.size();
        if (mStreamsTried >= count) {
            mHandler.removeCallbacks(mWatchdog);
            releasePlayer();
            mBuffering.setVisibility(View.GONE);
            Toast.makeText(this, mAccountBusy && !mStarted
                    ? "Every account for " + channel.name + " is in use on another TV"
                    : (mCatchupStart > 0 ? "Couldn't play the recording of "
                    : "No working stream for ") + channel.name, Toast.LENGTH_LONG).show();
            return;
        }
        boolean silent = mCatchupStart > 0 && channel.archiveDays.get(mStreamIndex) == 0;
        if (count > 1 && !silent) {
            Toast.makeText(this, "Stream " + (mStreamIndex + 1) + " " + reason
                    + ", trying the next", Toast.LENGTH_SHORT).show();
        }
        mManualStream = false;
        if (mCatchupStart > 0 && mPlayer != null && mStarted) mRecordingStart = position();
        playStream((mStreamIndex + 1) % count);
    }

    // ---- Catch-up position ----

    /** Wall-clock time being shown in a recording. */
    private long position() {
        return mRecordingStart + (mPlayer == null ? 0 : mPlayer.getCurrentPosition());
    }

    private void skip(long deltaMs) {
        if (mPlayer == null) return;
        seekTo(position() + deltaMs);
    }

    /**
     * Moves within the programme. Seeks in the recording when it allows it; otherwise asks the
     * panel for a new recording from that minute.
     */
    private void seekTo(long time) {
        long latest = Math.min(mCatchupEnd, System.currentTimeMillis() - LIVE_EDGE_MS);
        long target = Math.max(mCatchupStart, Math.min(time, latest));
        if (mPlayer != null && mPlayer.isCurrentMediaItemSeekable()
                && target >= mRecordingStart) {
            mPlayer.seekTo(target - mRecordingStart);
        } else {
            mStreamsTried = 0;
            mRecordingStart = target - target % 60_000;
            playStream(mStreamIndex);
        }
        showOverlay(false);
    }

    private void togglePause() {
        if (mPlayer == null || mCatchupStart == 0) return;
        mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
        // A pause isn't a stall.
        mLastProgressMs = System.currentTimeMillis();
        showOverlay(mOverlayButtons.hasFocus());
    }

    // ---- Subtitles ----

    private void toggleSubtitles() {
        mSubtitles = !mSubtitles;
        mPrefs.edit().putBoolean(PREF_SUBTITLES, mSubtitles).apply();
        applySubtitles();
        updateButtons();
        if (mSubtitles && mPlayer != null
                && !mPlayer.getCurrentTracks().containsType(C.TRACK_TYPE_TEXT)) {
            Toast.makeText(this, "This stream has no subtitles", Toast.LENGTH_SHORT).show();
        }
    }

    /** Subtitles on picks the device language's track, or any untagged one (e.g. captions). */
    private void applySubtitles() {
        if (mPlayer == null) return;
        TrackSelectionParameters params = mPlayer.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !mSubtitles)
                .setPreferredTextLanguage(mSubtitles ? Locale.getDefault().getLanguage() : null)
                .setSelectUndeterminedTextLanguage(mSubtitles)
                .build();
        mPlayer.setTrackSelectionParameters(params);
    }

    // ---- Streams ----

    /** Saves a stream as the channel's first, in the web config and the guide store. */
    private void promoteStream(GuideData.Channel channel, int index) {
        int streamId = channel.streams.remove(index);
        String name = channel.streamNames.remove(index);
        int days = channel.archiveDays.remove(index);
        channel.streams.add(0, streamId);
        channel.streamNames.add(0, name);
        channel.archiveDays.add(0, days);
        mStreamIndex = 0;
        List<Integer> streams = new java.util.ArrayList<>(channel.streams);
        List<String> names = new java.util.ArrayList<>(channel.streamNames);
        List<Integer> archive = new java.util.ArrayList<>(channel.archiveDays);
        mExecutor.execute(() -> {
            try {
                ChannelConfig.edit(getApplicationContext(),
                        c -> c.promoteStream(channel.streamId, streamId));
                ContentValues values = new ContentValues();
                values.put("streams", new JSONArray(streams).toString());
                values.put("stream_names", new JSONArray(names).toString());
                values.put("archive", new JSONArray(archive).toString());
                GuideStore.get(getApplicationContext()).getWritableDatabase().update(
                        GuideStore.CHANNELS, values, "stream_id = ?",
                        new String[]{String.valueOf(channel.streamId)});
            } catch (Exception e) {
                Log.w(TAG, "Could not save stream order", e);
            }
        });
    }

    /**
     * Lists the channel's streams with their source and catch-up. While watching catch-up,
     * only streams with a recording can be picked.
     */
    private void showStreamMenu() {
        GuideData.Channel channel = mChannels.get(mIndex);
        boolean catchup = mCatchupStart > 0;
        java.util.Map<Integer, String> sourceNames = Sources.names(this);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.stream_menu);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        ((TextView) dialog.findViewById(R.id.stream_menu_title)).setText(
                (catchup ? "Catch-up streams for " : "Streams for ") + channel.name);
        ListView list = dialog.findViewById(R.id.stream_menu_list);
        list.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return channel.streams.size();
            }

            @Override
            public Object getItem(int position) {
                return channel.streams.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public boolean isEnabled(int position) {
                return !catchup || channel.archiveDays.get(position) > 0;
            }

            @Override
            public View getView(int position, View view, ViewGroup parent) {
                if (view == null) {
                    view = LayoutInflater.from(PlayerActivity.this)
                            .inflate(R.layout.stream_row, parent, false);
                }
                int streamId = channel.streams.get(position);
                int days = channel.archiveDays.get(position);
                view.findViewById(R.id.stream_current).setVisibility(
                        position == mStreamIndex ? View.VISIBLE : View.INVISIBLE);
                ((TextView) view.findViewById(R.id.stream_source)).setText(
                        Sources.name(sourceNames, streamId));
                ((TextView) view.findViewById(R.id.stream_name)).setText(
                        channel.streamNames.get(position));
                TextView chip = view.findViewById(R.id.stream_catchup);
                chip.setVisibility(days > 0 ? View.VISIBLE : View.GONE);
                chip.setText("Catch-up " + days + (days == 1 ? " day" : " days"));
                view.setAlpha(isEnabled(position) ? 1f : 0.4f);
                return view;
            }
        });
        list.setOnItemClickListener((parent, v, which, id) -> {
            dialog.dismiss();
            if (which == mStreamIndex && mStarted) return;
            // A stream picked by hand plays for now; it doesn't change the order.
            mStreamsTried = 0;
            mManualStream = true;
            if (catchup && mPlayer != null && mStarted) mRecordingStart = position();
            playStream(which);
        });
        dialog.setOnShowListener(d -> {
            list.requestFocus();
            list.setSelection(mStreamIndex);
        });
        dialog.show();
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayerView.setPlayer(null);
            mPlayer.release();
            mPlayer = null;
            Connections.released(mPlayerSource);
        }
    }

    // ---- Banner ----

    /** Shows the banner; with focusButtons, the first button takes focus. */
    private void showOverlay(boolean focusButtons) {
        GuideData.Channel channel = mChannels.get(mIndex);
        String title = channel.number + "  " + channel.name;
        if (channel.streams.size() > 1) {
            title += "   ·   Stream " + (mStreamIndex + 1) + " of " + channel.streams.size();
        }
        mOverlayChannel.setText(title);
        int days = channel.catchupDays();
        mOverlayBadge.setVisibility(days > 0 ? View.VISIBLE : View.GONE);
        mOverlayBadge.setBackgroundResource(mCatchupStart > 0
                ? R.drawable.chip_catchup : R.drawable.chip_small);
        mOverlayBadge.setText(mCatchupStart > 0 ? "Watching catch-up"
                : "Catch-up " + days + (days == 1 ? " day" : " days"));
        bindPrograms(channel);
        updateButtons();
        mOverlayLogo.setImageBitmap(GuideData.cachedLogo(channel.id));
        long channelId = channel.id;
        mExecutor.execute(() -> {
            Bitmap logo = GuideData.logo(getApplicationContext(), channelId);
            // Programs may have moved on since the guide loaded.
            GuideData.refreshPrograms(getApplicationContext(),
                    Collections.singletonList(channel));
            runOnUiThread(() -> {
                if (isDestroyed() || mChannels.get(mIndex).id != channelId) return;
                mOverlayLogo.setImageBitmap(logo);
                bindPrograms(channel);
                updateButtons();
            });
        });
        boolean wasVisible = mOverlay.getVisibility() == View.VISIBLE;
        mOverlay.setVisibility(View.VISIBLE);
        if (focusButtons) {
            if (!mOverlayButtons.hasFocus()) firstButton().requestFocus();
        } else if (!wasVisible) {
            mPlayerView.requestFocus();
        }
        scheduleHide();
        mHandler.removeCallbacks(mOverlayTick);
        mHandler.postDelayed(mOverlayTick, OVERLAY_TICK_MS);
    }

    private void scheduleHide() {
        mHandler.removeCallbacks(mHideOverlay);
        mHandler.postDelayed(mHideOverlay, OVERLAY_MS);
    }

    private View firstButton() {
        for (View b : new View[]{mBtnPlay, mBtnStartOver, mBtnLive, mBtnStreams, mBtnSubtitles}) {
            if (b.getVisibility() == View.VISIBLE) return b;
        }
        return mBtnSubtitles;
    }

    private void updateButtons() {
        GuideData.Channel channel = mChannels.get(mIndex);
        boolean catchup = mCatchupStart > 0;
        GuideData.Program now = channel.now;
        mBtnPlay.setVisibility(catchup ? View.VISIBLE : View.GONE);
        mBtnPlay.setText(mPlayer != null && !mPlayer.getPlayWhenReady() ? "Play" : "Pause");
        mBtnLive.setVisibility(catchup ? View.VISIBLE : View.GONE);
        mBtnStartOver.setVisibility(catchup || (now != null
                && channel.canCatchUp(now, System.currentTimeMillis())) ? View.VISIBLE : View.GONE);
        mBtnStreams.setVisibility(channel.streams.size() > 1 ? View.VISIBLE : View.GONE);
        mBtnSubtitles.setText(mSubtitles ? "Subtitles: On" : "Subtitles: Off");
        // Focus mustn't stay on a button that just went away.
        View focused = getCurrentFocus();
        if (focused != null && focused.getVisibility() != View.VISIBLE
                && mOverlay.getVisibility() == View.VISIBLE) {
            firstButton().requestFocus();
        }
    }

    private void bindPrograms(GuideData.Channel channel) {
        if (mCatchupStart > 0) {
            bindRecording();
            return;
        }
        GuideData.Program p = channel.now;
        String now = p != null ? mTimeFormat.format(new Date(p.startMs)) + " – "
                + mTimeFormat.format(new Date(p.endMs)) + "   " + p.title : "";
        if (channel.streams.size() > 1) {
            now = (now.isEmpty() ? "" : now + "   ·   ")
                    + channel.streamNames.get(Math.min(mStreamIndex, channel.streamNames.size() - 1));
        }
        mOverlayNow.setText(now);
        mOverlayProgress.setVisibility(p != null ? View.VISIBLE : View.GONE);
        if (p != null) {
            mOverlayProgress.setProgress((int) (p.progress(System.currentTimeMillis()) * 1000));
        }
        GuideData.Program next = channel.next;
        mOverlayNext.setText(next != null
                ? "Next  " + mTimeFormat.format(new Date(next.startMs)) + "  " + next.title : "");
    }

    /** The banner during catch-up: the programme, how far in, and the skip keys. */
    private void bindRecording() {
        long length = Math.max(1, mCatchupEnd - mCatchupStart);
        long at = Math.max(0, Math.min(length, position() - mCatchupStart));
        mOverlayNow.setText(mTimeFormat.format(new Date(mCatchupStart)) + " – "
                + mTimeFormat.format(new Date(mCatchupEnd)) + "   "
                + (mCatchupTitle == null ? "" : mCatchupTitle)
                + "   ·   " + clock(at) + " / " + clock(length));
        mOverlayProgress.setVisibility(View.VISIBLE);
        mOverlayProgress.setProgress((int) (at * 1000 / length));
        mOverlayNext.setText("◀ ▶  skip 30 s     ⏪ ⏩  skip 2 min");
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static String titleAt(GuideData.Channel channel, long time) {
        for (GuideData.Program p : channel.programs) {
            if (p.startMs <= time && time < p.endMs) return p.title;
        }
        return null;
    }
}
