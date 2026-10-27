package ru.minisip.app;
import ru.minisip.R;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import ru.minisip.log.LogBuffer;

/**
 * RecyclerView-based log panel. Subscribes to LogBuffer.Delta events and
 * appends rows incrementally; FollowPolicy decides whether new records
 * auto-scroll into view. Thresholds are in visual text lines, not records.
 */
public final class LogView {

    public final LinearLayout panel;
    public final Switch toggle;
    public Runnable onClear;
    public Runnable onSave;

    private final LogBuffer buffer;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private final TextView placeholder;
    private final TextView newBadge;
    private final FollowPolicy follow = new FollowPolicy();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float lineHeightPx;

    private boolean active = false;
    private boolean pendingDelta = false;
    private boolean userScrolling = false;
    private long seenUpToSeq = 0;
    private long pendingReplaced = -1;

    private final LogBuffer.Delta deltaListener = new LogBuffer.Delta() {
        @Override public void onAppended(long from, long to) { scheduleDelta(); }
        @Override public void onTrimmed(long newFirstSeq) { scheduleDelta(); }
        @Override public void onReplaced(long seq) { pendingReplaced = seq; scheduleDelta(); }
        @Override public void onCleared() { scheduleDelta(); }
    };

    public LogView(Context ctx, LogBuffer buffer) {
        this.buffer = buffer;

        TextView probe = new TextView(ctx);
        probe.setTextAppearance(android.R.style.TextAppearance_Small);
        probe.setTypeface(Typeface.MONOSPACE);
        probe.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        this.lineHeightPx = Math.max(1f, probe.getLineHeight());

        int cp = dp(ctx, 6);

        toggle = new Switch(ctx);
        toggle.setPadding(cp, dp(ctx, 2), cp, dp(ctx, 2));
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);


        toggle.setOnCheckedChangeListener((v, checked) -> {
            panel.setVisibility(checked ? View.VISIBLE : View.GONE);
            setActive(checked);
        });
        list = new RecyclerView(ctx);
        list.setLayoutManager(new LinearLayoutManager(ctx));
        list.setAdapter(adapter);
        list.setItemAnimator(null);
        list.setVerticalScrollBarEnabled(true);
        list.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 44)));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14808080);
        bg.setStroke(dp(ctx, 1), 0x33808080);
        bg.setCornerRadius(dp(ctx, 8));
        list.setBackground(bg);

        placeholder = new TextView(ctx);
        placeholder.setText("Log is empty");
        placeholder.setTextAppearance(android.R.style.TextAppearance_Small);
        placeholder.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        placeholder.setVisibility(View.VISIBLE);

        newBadge = new TextView(ctx);
        newBadge.setTextAppearance(android.R.style.TextAppearance_Small);
        newBadge.setTextColor(0xFFFFFFFF);
        newBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        newBadge.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xFF1976D2);
        badgeBg.setCornerRadius(dp(ctx, 12));
        newBadge.setBackground(badgeBg);
        newBadge.setVisibility(View.GONE);
        newBadge.setOnClickListener(v -> {
            follow.onFollowTap();
            seenUpToSeq = buffer.nextSeq();
            newBadge.setVisibility(View.GONE);
            scrollToBottom();
        });
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.gravity = Gravity.BOTTOM | Gravity.END;
        int bm = dp(ctx, 6);
        badgeLp.setMargins(bm, bm, bm, bm);
        newBadge.setLayoutParams(badgeLp);

        FrameLayout listWrap = new FrameLayout(ctx);
        listWrap.addView(list);
        listWrap.addView(placeholder);
        listWrap.addView(newBadge);

        LinearLayout band = new LinearLayout(ctx);
        band.setOrientation(LinearLayout.HORIZONTAL);
        View left = new View(ctx);
        band.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.085f));
        band.addView(listWrap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.83f));
        View right = new View(ctx);
        band.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.085f));
        LinearLayout.LayoutParams bandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bandLp.topMargin = dp(ctx, 8);
        panel.addView(band, bandLp);

        LinearLayout footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        TextView up = new TextView(ctx);
        up.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_expand_less, 0, 0, 0);
        up.setContentDescription("Scroll to top");
        up.setPadding(cp, dp(ctx, 2), cp, dp(ctx, 2));
        up.setClickable(true);
        up.setFocusable(true);
        up.setOnClickListener(v -> {
            if (adapter.getItemCount() > 0) {
                list.scrollToPosition(0);
                list.post(() -> {
                    follow.onUserScroll(computeHiddenLines());
                    flush();
                });
            }
        });
        footer.addView(up);

        TextView down = new TextView(ctx);
        down.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_expand_more, 0, 0, 0);
        down.setContentDescription("Scroll to bottom");
        down.setPadding(cp, dp(ctx, 2), cp, dp(ctx, 2));
        down.setClickable(true);
        down.setFocusable(true);
        down.setOnClickListener(v -> {
            follow.onFollowTap();
            seenUpToSeq = buffer.nextSeq();
            newBadge.setVisibility(View.GONE);
            scrollToBottom();
        });
        footer.addView(down);

        View footerSpacer = new View(ctx);
        footer.addView(footerSpacer, new LinearLayout.LayoutParams(0, 0, 1f));

        TextView save = new TextView(ctx);
        save.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_save, 0, 0, 0);
        save.setContentDescription("Save log");
        save.setPadding(cp, dp(ctx, 2), cp, dp(ctx, 2));
        save.setClickable(true);
        save.setFocusable(true);
        save.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(ctx)
                    .setTitle("Save log")
                    .setItems(new String[]{"Copy to clipboard", "Save to file"}, (d, which) -> {
                        if (which == 0) {
                            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("log", buffer.text()));
                        } else {
                            if (onSave != null) onSave.run();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        footer.addView(save);

        TextView clear = new TextView(ctx);
        clear.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0);
        clear.setContentDescription("Clear log");
        clear.setPadding(cp, dp(ctx, 2), cp, dp(ctx, 2));
        clear.setClickable(true);
        clear.setFocusable(true);
        clear.setOnClickListener(v -> {
            MainActivity.showCustomConfirmDialog(ctx,
                    "Clear log",
                    "Delete all log records?",
                    () -> {
                        if (onClear != null) onClear.run();
                    },
                    null);
        });
        footer.addView(clear);

        LinearLayout footerBand = new LinearLayout(ctx);
        footerBand.setOrientation(LinearLayout.HORIZONTAL);
        View fLeft = new View(ctx);
        footerBand.addView(fLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.085f));
        footerBand.addView(footer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.83f));
        View fRight = new View(ctx);
        footerBand.addView(fRight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.085f));
        panel.addView(footerBand);

        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolling = true;
                } else if (state == RecyclerView.SCROLL_STATE_IDLE && userScrolling) {
                    userScrolling = false;
                    follow.onUserScroll(computeHiddenLines());
                    if (follow.isFollowing()) seenUpToSeq = buffer.nextSeq();
                    flush();
                }
            }
        });
    }

    /** Subscribes/unsubscribes from LogBuffer; call from onResume/onPause or toggle. */
    public void setActive(boolean on) {
        if (active == on) return;
        active = on;
        if (on) {
            adapter.clearAll();
            follow.reset();
            long first = buffer.firstSeq();
            long next = buffer.nextSeq();
            if (next > first) {
                adapter.append(first, buffer.linesFrom(first));
            }
            seenUpToSeq = next;
            buffer.setDeltaListener(deltaListener);
            flush();
            if (adapter.getItemCount() > 0) {
                list.post(() -> list.scrollToPosition(adapter.getItemCount() - 1));
            }
        } else {
            buffer.setDeltaListener(null);
        }
    }

    private void scheduleDelta() {
        if (pendingDelta) return;
        pendingDelta = true;
        main.post(this::flush);
    }

    private void flush() {
        pendingDelta = false;
        if (!active) return;

        long bufferFirst = buffer.firstSeq();
        long bufferNext = buffer.nextSeq();

        if (!adapter.isEmpty() && bufferFirst > adapter.firstSeq()) {
            adapter.trimTo(bufferFirst);
        }

        if (pendingReplaced >= 0) {
            long seq = pendingReplaced;
            pendingReplaced = -1;
            if (seq >= bufferFirst && seq < bufferNext) {
                List<String> v = buffer.linesFrom(seq);
                if (!v.isEmpty()) adapter.replace(seq, v.get(0));
            }
        }

        long from = adapter.isEmpty() ? bufferFirst : adapter.lastSeq() + 1;
        if (bufferNext > from) {
            List<String> added = buffer.linesFrom(from);
            if (!added.isEmpty()) adapter.append(from, added);
        } else if (!adapter.isEmpty() && adapter.lastSeq() >= bufferNext) {
            adapter.clearAll();
            if (bufferNext > bufferFirst) {
                adapter.append(bufferFirst, buffer.linesFrom(bufferFirst));
            }
        }

        placeholder.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);

        if (follow.isFollowing()) {
            if (!adapter.isEmpty()) list.scrollToPosition(adapter.getItemCount() - 1);
            seenUpToSeq = bufferNext;
            newBadge.setVisibility(View.GONE);
        } else {
            long unseen = Math.max(0, bufferNext - Math.max(seenUpToSeq, bufferFirst));
            if (unseen > 0) {
                newBadge.setText(unseen + " new ▼");
                newBadge.setVisibility(View.VISIBLE);
            } else {
                newBadge.setVisibility(View.GONE);
            }
        }

        resizeToContent();
    }

    private double computeHiddenLines() {
        int range = list.computeVerticalScrollRange();
        int extent = list.computeVerticalScrollExtent();
        int offset = list.computeVerticalScrollOffset();
        int hidden = Math.max(0, range - extent - offset);
        return hidden / (double) lineHeightPx;
    }

    private void scrollToBottom() {
        if (adapter.getItemCount() > 0) {
            list.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private void resizeToContent() {
        if (panel.getVisibility() != View.VISIBLE) return;
        list.post(() -> {
            int count = adapter.getItemCount();
            if (count == 0) {
                setListHeight(dp(list.getContext(), 44));
                return;
            }
            int visible = list.getChildCount();
            if (visible == 0) return;
            int total = 0;
            for (int i = 0; i < visible; i++) total += list.getChildAt(i).getHeight();
            int avg = total / visible;
            if (avg <= 0) avg = (int) lineHeightPx;
            int target = Math.max(dp(list.getContext(), 44),
                    Math.min(dp(list.getContext(), 300), avg * count + dp(list.getContext(), 8)));
            setListHeight(target);
        });
    }

    private void setListHeight(int px) {
        ViewGroup.LayoutParams lp = list.getLayoutParams();
        if (lp.height != px) {
            lp.height = px;
            list.setLayoutParams(lp);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    /** Fixed-size adapter for the log list. Stable ids are the log sequence numbers. */
    private static final class Adapter extends RecyclerView.Adapter<Adapter.Row> {
        private final List<Long> seqs = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();

        Adapter() {
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int pos) {
            return seqs.get(pos);
        }

        @Override
        public int getItemCount() {
            return seqs.size();
        }

        boolean isEmpty() {
            return seqs.isEmpty();
        }

        long firstSeq() {
            return seqs.isEmpty() ? Long.MIN_VALUE : seqs.get(0);
        }

        long lastSeq() {
            return seqs.isEmpty() ? Long.MIN_VALUE : seqs.get(seqs.size() - 1);
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextAppearance(android.R.style.TextAppearance_Small);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(false);
            int p = dp(parent.getContext(), 6);
            tv.setPadding(p, 2, p, 2);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Row(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Row h, int pos) {
            h.tv.setText(lines.get(pos));
        }

        void append(long from, List<String> added) {
            int start = seqs.size();
            for (int i = 0; i < added.size(); i++) {
                seqs.add(from + i);
                lines.add(added.get(i));
            }
            notifyItemRangeInserted(start, added.size());
        }

        void trimTo(long newFirstSeq) {
            int remove = 0;
            while (remove < seqs.size() && seqs.get(remove) < newFirstSeq) remove++;
            if (remove > 0) {
                seqs.subList(0, remove).clear();
                lines.subList(0, remove).clear();
                notifyItemRangeRemoved(0, remove);
            }
        }

        void replace(long seq, String newLine) {
            int idx = seqs.indexOf(seq);
            if (idx >= 0) {
                lines.set(idx, newLine);
                notifyItemChanged(idx);
            }
        }

        void clearAll() {
            int n = seqs.size();
            if (n == 0) return;
            seqs.clear();
            lines.clear();
            notifyItemRangeRemoved(0, n);
        }

        static final class Row extends RecyclerView.ViewHolder {
            final TextView tv;
            Row(TextView v) {
                super(v);
                tv = v;
            }
        }
    }
}
