package com.example.android.sampletvinput;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FyrTuner's guide, laid out like a TV programme guide: group chips, details and a live
 * preview of the highlighted channel, then a time grid of channels and programmes with a
 * line at the current time. Selecting a programme plays its channel in PlayerActivity; on a
 * channel with catch-up (marked ↺) the grid scrolls back into the past, and selecting a
 * programme that has ended plays its recording.
 *
 * The grid handles the D-pad itself (Android's focus search doesn't suit a time grid):
 * Left/Right move between programmes, scrolling the window in half-hour steps; Up/Down move
 * between channels at the same time; Up from the top row or Back returns to the chips.
 */
public class GuideActivity extends Activity {
    private static final long MINUTE = 60_000;
    private static final long DAY = 24 * 60 * MINUTE;
    private static final long STEP_MS = 30 * MINUTE;
    private static final long WINDOW_MS = 2 * 60 * MINUTE;
    // The sync stores 24 hours ahead; keep the window inside it.
    private static final long MAX_AHEAD_MS = 22 * 60 * MINUTE;
    private static final long TICK_MS = 30_000;
    private static final long RELOAD_DEBOUNCE_MS = 500;
    private static final long PREVIEW_DELAY_MS = 1_000;
    // Space between the channel column and the programme cells (guide_grid_row.xml).
    private static final int COLUMN_GAP_DP = 3;

    private LinearLayout mChips;
    private RecyclerView mGrid;
    private FrameLayout mRuler;
    private TextView mRulerDate;
    private View mNowLine;
    private View mGridArea;
    private TextView mEmpty;
    private TextView mHeroTitle;
    private TextView mHeroTime;
    private TextView mHeroDescription;
    private StyledPlayerView mPreview;
    private ImageView mPreviewLogo;

    private final RowAdapter mAdapter = new RowAdapter();
    private LinearLayoutManager mLayoutManager;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mLogoExecutor = Executors.newFixedThreadPool(2);
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DateFormat mTimeFormat;
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("EEE d/M", Locale.getDefault());

    private List<GuideData.Group> mGroups = new ArrayList<>();
    private List<GuideData.Channel> mChannels = Collections.emptyList();
    private String mGroupId = GuideData.ALL_GROUP_ID;
    private View mSelectedChip;

    private boolean mGridMode;
    // The first load opens in the grid; later reloads keep whatever had focus.
    private boolean mLoaded;
    private int mRow;
    private long mFocusTime;
    private long mWindowStart;
    // Whether the window moves on with the clock; not once the user scrolls back.
    private boolean mFollowNow = true;
    private float mPxPerMs;

    private ExoPlayer mPreviewPlayer;
    private long mPreviewChannelId = -1;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            onTick();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable mReload = this::reload;
    private final Runnable mStartPreview = this::startPreview;

    // Reload when a sync has written a new guide.
    private final Runnable mGuideChanged = () -> {
        mHandler.removeCallbacks(mReload);
        mHandler.postDelayed(mReload, RELOAD_DEBOUNCE_MS);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.guide_activity);
        WebServerService.start(this);
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);

        mChips = findViewById(R.id.chips);
        mGrid = findViewById(R.id.grid);
        mRuler = findViewById(R.id.ruler);
        mRulerDate = findViewById(R.id.ruler_date);
        mNowLine = findViewById(R.id.now_line);
        mGridArea = findViewById(R.id.grid_area);
        mEmpty = findViewById(R.id.empty);
        mHeroTitle = findViewById(R.id.hero_title);
        mHeroTime = findViewById(R.id.hero_time);
        mHeroDescription = findViewById(R.id.hero_description);
        mPreview = findViewById(R.id.preview);
        mPreview.setUseController(false);
        mPreviewLogo = findViewById(R.id.preview_logo);

        mLayoutManager = new LinearLayoutManager(this);
        mGrid.setLayoutManager(mLayoutManager);
        mGrid.setAdapter(mAdapter);
        mGrid.setItemAnimator(null);
        mGrid.setOnFocusChangeListener((v, focused) -> {
            if (focused) enterGrid();
        });
        mGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) {
                mPxPerMs = 0;
                render();
            }
        });

        long now = System.currentTimeMillis();
        mWindowStart = floorStep(now);
        mFocusTime = now;
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuideStore.addListener(mGuideChanged);
        mHandler.post(mTick);
        reload();
    }

    @Override
    protected void onPause() {
        GuideStore.removeListener(mGuideChanged);
        mHandler.removeCallbacksAndMessages(null);
        // The account allows one stream: free it before PlayerActivity starts its own.
        stopPreview();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        mLogoExecutor.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        mExecutor.execute(() -> {
            List<GuideData.Group> groups = GuideData.load(getApplicationContext());
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                mGroups = groups;
                buildChips();
            });
        });
    }

    // ---- Keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                startActivity(new Intent(this, MainActivity.class));
            }
            return true;
        }
        if (!mGridMode || mChannels.isEmpty()) return super.dispatchKeyEvent(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (mRow == 0) exitGrid();
                    else moveRow(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveRow(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) moveHorizontal(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveHorizontal(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down) play();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) exitGrid();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void enterGrid() {
        if (mChannels.isEmpty()) return;
        mGridMode = true;
        mRow = Math.min(mRow, mChannels.size() - 1);
        render();
        schedulePreview();
    }

    private void exitGrid() {
        mGridMode = false;
        if (mSelectedChip != null) mSelectedChip.requestFocus();
        render();
    }

    private void moveRow(int delta) {
        int row = Math.max(0, Math.min(mChannels.size() - 1, mRow + delta));
        if (row == mRow) return;
        mRow = row;
        int first = mLayoutManager.findFirstCompletelyVisibleItemPosition();
        int last = mLayoutManager.findLastCompletelyVisibleItemPosition();
        if (first != RecyclerView.NO_POSITION && row < first) {
            mLayoutManager.scrollToPositionWithOffset(row, 0);
        } else if (last != RecyclerView.NO_POSITION && row > last) {
            mLayoutManager.scrollToPositionWithOffset(row - (last - first), 0);
        }
        render();
        schedulePreview();
    }

    private void moveHorizontal(int direction) {
        GuideData.Channel channel = mChannels.get(mRow);
        List<GuideData.Program> programs = channel.programs;
        GuideData.Program current = programAt(programs, mFocusTime);
        long now = System.currentTimeMillis();
        long maxStart = floorStep(now) + MAX_AHEAD_MS;
        // Catch-up channels scroll back as far as their recordings go.
        long minStart = floorStep(now - channel.catchupDays() * DAY);
        int index = current == null ? -1 : programs.indexOf(current);

        if (direction > 0) {
            GuideData.Program next = index >= 0 && index + 1 < programs.size()
                    ? programs.get(index + 1) : null;
            if (next == null) {
                // No more listings: just scroll the window on.
                if (mWindowStart + STEP_MS > maxStart) return;
                mWindowStart += STEP_MS;
                mFocusTime = Math.max(mFocusTime, mWindowStart);
            } else {
                while (next.startMs >= mWindowStart + WINDOW_MS - STEP_MS / 3
                        && mWindowStart + STEP_MS <= maxStart) {
                    mWindowStart += STEP_MS;
                }
                mFocusTime = Math.max(next.startMs, mWindowStart);
            }
        } else {
            GuideData.Program previous = index > 0 ? programs.get(index - 1) : null;
            if (previous == null || previous.endMs <= minStart) {
                if (mWindowStart - STEP_MS < minStart) return;
                mWindowStart -= STEP_MS;
                mFocusTime = Math.max(Math.min(mFocusTime, mWindowStart + WINDOW_MS - 1),
                        mWindowStart);
            } else {
                while (previous.startMs < mWindowStart && mWindowStart - STEP_MS >= minStart) {
                    mWindowStart -= STEP_MS;
                }
                mFocusTime = Math.max(previous.startMs, mWindowStart);
            }
        }
        mFollowNow = mWindowStart >= floorStep(now);
        render();
    }

    /** Plays the channel live, or a programme that has ended from catch-up. */
    private void play() {
        GuideData.Channel channel = mChannels.get(mRow);
        GuideData.Program p = programAt(channel.programs, mFocusTime);
        long now = System.currentTimeMillis();
        stopPreview();
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_GROUP_ID, mGroupId);
        intent.putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id);
        if (p != null && p.endMs <= now) {
            if (channel.canCatchUp(p, now)) {
                intent.putExtra(PlayerActivity.EXTRA_CATCHUP_START, p.startMs);
                intent.putExtra(PlayerActivity.EXTRA_CATCHUP_END, p.endMs);
            } else {
                Toast.makeText(this, "That programme isn't on catch-up; playing live",
                        Toast.LENGTH_SHORT).show();
            }
        }
        startActivity(intent);
    }

    // ---- Chips ----

    private void buildChips() {
        boolean chipsFocused = mLoaded && (mChips.hasFocus() || !mGridMode);
        long focusedChannel = mChannels.isEmpty() ? -1 : mChannels.get(mRow).id;
        mChips.removeAllViews();
        mSelectedChip = null;
        GuideData.Group selected = mGroups.isEmpty() ? null : mGroups.get(0);
        for (GuideData.Group g : mGroups) {
            if (g.id.equals(mGroupId)) selected = g;
        }
        for (GuideData.Group group : mGroups) {
            TextView chip = newChip(group.name);
            chip.setOnFocusChangeListener((v, focused) -> {
                if (focused) selectGroup(group, v);
            });
            chip.setOnClickListener(v -> mGrid.requestFocus());
            mChips.addView(chip);
            if (group == selected) mSelectedChip = chip;
        }
        TextView settings = newChip("Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        mChips.addView(settings);

        boolean empty = selected == null || selected.channels.isEmpty();
        mGridArea.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            mChannels = Collections.emptyList();
            mGridMode = false;
            String url = WebServerService.pageUrl();
            mEmpty.setText("No channels yet.\nOpen Settings, or manage channels at "
                    + (url != null ? url : "the web manager") + ".");
            settings.requestFocus();
            render();
            return;
        }

        // Keep the same group and channel across a reload.
        mLoaded = true;
        mGroupId = selected.id;
        mChannels = selected.channels;
        mSelectedChip.setSelected(true);
        int row = 0;
        for (int i = 0; i < mChannels.size(); i++) {
            if (mChannels.get(i).id == focusedChannel) row = i;
        }
        mRow = row;
        mAdapter.notifyDataSetChanged();
        if (chipsFocused) {
            mGridMode = false;
            mSelectedChip.requestFocus();
        } else {
            mGrid.requestFocus();
        }
        render();
        schedulePreview();
    }

    private TextView newChip(String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setTextColor(getColorStateList(R.color.guide_chip_text));
        chip.setBackgroundResource(R.drawable.guide_chip);
        chip.setFocusable(true);
        chip.setNextFocusDownId(R.id.grid);
        chip.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        chip.setLayoutParams(lp);
        return chip;
    }

    private void selectGroup(GuideData.Group group, View chip) {
        mGridMode = false;
        if (mSelectedChip != null) mSelectedChip.setSelected(false);
        mSelectedChip = chip;
        chip.setSelected(true);
        if (group.id.equals(mGroupId) && mChannels == group.channels) {
            render();
            return;
        }
        mGroupId = group.id;
        mChannels = group.channels;
        mRow = 0;
        long now = System.currentTimeMillis();
        mWindowStart = floorStep(now);
        mFollowNow = true;
        mFocusTime = now;
        mLayoutManager.scrollToPositionWithOffset(0, 0);
        mAdapter.notifyDataSetChanged();
        render();
        schedulePreview();
    }

    // ---- Rendering ----

    private void render() {
        if (mPxPerMs == 0) {
            int width = mGrid.getWidth() - getResources().getDimensionPixelSize(
                    R.dimen.guide_channel_column) - dp(COLUMN_GAP_DP);
            if (width <= 0) {
                mGrid.post(this::render);
                return;
            }
            mPxPerMs = width / (float) WINDOW_MS;
        }
        renderRuler();
        renderHero();
        mAdapter.notifyItemRangeChanged(0, mAdapter.getItemCount());
    }

    private void renderRuler() {
        long now = System.currentTimeMillis();
        // Scrolled to another day (catch-up): say which.
        String day = mDateFormat.format(new Date(mWindowStart));
        String today = mDateFormat.format(new Date(now));
        mRulerDate.setText(day.equals(today)
                ? today + " · " + mTimeFormat.format(new Date(now))
                : "↺ " + day);
        mRuler.removeAllViews();
        for (long t = mWindowStart; t < mWindowStart + WINDOW_MS; t += STEP_MS) {
            TextView label = new TextView(this);
            label.setText(mTimeFormat.format(new Date(t)));
            label.setTextColor(getColor(R.color.guide_muted));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.leftMargin = dp(COLUMN_GAP_DP) + Math.round((t - mWindowStart) * mPxPerMs) + dp(6);
            mRuler.addView(label, lp);
        }
        if (now >= mWindowStart && now < mWindowStart + WINDOW_MS && !mChannels.isEmpty()) {
            mNowLine.setVisibility(View.VISIBLE);
            mNowLine.setTranslationX(getResources().getDimensionPixelSize(
                    R.dimen.guide_channel_column) + dp(COLUMN_GAP_DP) + (now - mWindowStart) * mPxPerMs);
        } else {
            mNowLine.setVisibility(View.GONE);
        }
    }

    private void renderHero() {
        if (mChannels.isEmpty()) {
            mHeroTitle.setText("");
            mHeroTime.setText("");
            mHeroDescription.setText("");
            return;
        }
        GuideData.Channel channel = mChannels.get(mRow);
        GuideData.Program p = programAt(channel.programs, mFocusTime);
        mHeroTitle.setText(p != null ? p.title : channel.name);
        StringBuilder time = new StringBuilder();
        if (p != null) {
            time.append(mTimeFormat.format(new Date(p.startMs))).append(" – ")
                    .append(mTimeFormat.format(new Date(p.endMs)));
            long now = System.currentTimeMillis();
            int i = channel.programs.indexOf(p);
            if (p.endMs <= now) {
                time.append(channel.canCatchUp(p, now) ? "     ↺ Catch-up: press OK to watch"
                        : "     Not on catch-up");
            } else if (p.startMs <= now && channel.canCatchUp(p, now)) {
                time.append("     ↺ Can start over");
            } else if (i >= 0 && i + 1 < channel.programs.size()) {
                time.append("     Up Next: ").append(channel.programs.get(i + 1).title);
            }
        }
        mHeroTime.setText(time);
        mHeroDescription.setText(p != null && p.description != null ? p.description : "");
    }

    private void onTick() {
        long now = System.currentTimeMillis();
        // Keep the window on the current half hour unless the user scrolled ahead or back.
        if (mFollowNow && mWindowStart < floorStep(now)) {
            mWindowStart = floorStep(now);
            mFocusTime = Math.max(mFocusTime, now);
        }
        render();
    }

    // ---- Preview ----

    private void schedulePreview() {
        if (mChannels.isEmpty()) return;
        long channelId = mChannels.get(mRow).id;
        loadLogo(channelId, mPreviewLogo);
        if (channelId == mPreviewChannelId && mPreviewPlayer != null) return;
        stopPreview();
        mHandler.removeCallbacks(mStartPreview);
        mHandler.postDelayed(mStartPreview, PREVIEW_DELAY_MS);
    }

    private void startPreview() {
        if (isFinishing() || mChannels.isEmpty()) return;
        GuideData.Channel channel = mChannels.get(mRow);
        String url = Sources.streamUrl(this, channel.streams.get(0));
        if (url == null) return;
        stopPreview();
        mPreviewChannelId = channel.id;
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        mPreviewPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        mPreview.setPlayer(mPreviewPlayer);
        mPreviewPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) mPreview.setVisibility(View.VISIBLE);
            }
        });
        mPreviewPlayer.setMediaItem(MediaItem.fromUri(
                Uri.parse(url)));
        mPreviewPlayer.setPlayWhenReady(true);
        mPreviewPlayer.prepare();
    }

    private void stopPreview() {
        mHandler.removeCallbacks(mStartPreview);
        mPreview.setVisibility(View.INVISIBLE);
        if (mPreviewPlayer != null) {
            mPreview.setPlayer(null);
            mPreviewPlayer.release();
            mPreviewPlayer = null;
        }
        mPreviewChannelId = -1;
    }

    // ---- Helpers ----

    /** The programme airing at the given time, else the next one, else the last one. */
    private static GuideData.Program programAt(List<GuideData.Program> programs, long time) {
        GuideData.Program after = null;
        for (GuideData.Program p : programs) {
            if (p.startMs <= time && time < p.endMs) return p;
            if (p.startMs > time && after == null) after = p;
        }
        if (after != null) return after;
        return programs.isEmpty() ? null : programs.get(programs.size() - 1);
    }

    private static long floorStep(long time) {
        return time - time % STEP_MS;
    }

    private void loadLogo(long channelId, ImageView view) {
        view.setTag(channelId);
        Bitmap cached = GuideData.cachedLogo(channelId);
        view.setImageBitmap(cached);
        if (cached != null) return;
        mLogoExecutor.execute(() -> {
            Bitmap bitmap = GuideData.logo(getApplicationContext(), channelId);
            if (bitmap == null) return;
            view.post(() -> {
                if (Long.valueOf(channelId).equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private int dp(int value) {
        return Math.round(value * density());
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    // ---- Grid rows ----

    private class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.guide_grid_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            holder.bind(mChannels.get(position), position);
        }

        @Override
        public int getItemCount() {
            return mChannels.size();
        }
    }

    private class RowHolder extends RecyclerView.ViewHolder {
        final View channelCell;
        final TextView number;
        final View catchup;
        final ImageView logo;
        final FrameLayout programs;

        RowHolder(View view) {
            super(view);
            channelCell = view.findViewById(R.id.channel_cell);
            number = view.findViewById(R.id.number);
            catchup = view.findViewById(R.id.catchup);
            logo = view.findViewById(R.id.logo);
            programs = view.findViewById(R.id.programs);
        }

        void bind(GuideData.Channel channel, int position) {
            boolean focusedRow = mGridMode && position == mRow;
            channelCell.setSelected(focusedRow);
            number.setText(channel.number);
            catchup.setVisibility(channel.catchupDays() > 0 ? View.VISIBLE : View.GONE);
            loadLogo(channel.id, logo);
            if (logo.getDrawable() == null) {
                logo.setContentDescription(channel.name);
            }

            programs.removeAllViews();
            if (mPxPerMs == 0) return;
            long windowEnd = mWindowStart + WINDOW_MS;
            GuideData.Program focused = focusedRow ? programAt(channel.programs, mFocusTime) : null;
            long now = System.currentTimeMillis();
            int gap = dp(3);
            boolean any = false;
            for (GuideData.Program p : channel.programs) {
                if (p.endMs <= mWindowStart || p.startMs >= windowEnd) continue;
                int left = Math.round((Math.max(p.startMs, mWindowStart) - mWindowStart) * mPxPerMs);
                int right = Math.round((Math.min(p.endMs, windowEnd) - mWindowStart) * mPxPerMs);
                int width = right - left - gap;
                if (width < dp(4)) continue;
                any = true;
                // Programmes that can be watched again are marked.
                boolean recorded = p.endMs <= now && channel.canCatchUp(p, now);
                TextView cell = newCell(recorded ? "↺ " + p.title : p.title, p == focused);
                cell.setActivated(p.startMs <= now && now < p.endMs);
                programs.addView(cell, cellParams(left, width));
            }
            if (!any) {
                // Nothing listed in this window: one cell with the channel name.
                int width = Math.round(WINDOW_MS * mPxPerMs) - gap;
                programs.addView(newCell(channel.name, focusedRow), cellParams(0, width));
            }
        }

        private TextView newCell(String title, boolean selected) {
            TextView cell = new TextView(GuideActivity.this);
            cell.setText(TextUtils.isEmpty(title) ? "" : title);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            cell.setTextColor(getColorStateList(R.color.guide_cell_text));
            cell.setBackgroundResource(R.drawable.guide_cell);
            cell.setSingleLine(true);
            cell.setEllipsize(TextUtils.TruncateAt.END);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(8), 0, dp(6), 0);
            cell.setSelected(selected);
            return cell;
        }

        private FrameLayout.LayoutParams cellParams(int left, int width) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            lp.leftMargin = left;
            return lp;
        }
    }
}
