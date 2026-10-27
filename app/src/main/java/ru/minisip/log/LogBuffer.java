package ru.minisip.log;

import java.util.List;

/**
 * In-memory ring buffer log. Oldest lines are evicted when capacity is reached.
 * Listener callbacks execute on the calling thread; callers must post to main thread for UI updates.
 */
public interface LogBuffer extends LogSink {

    static LogBuffer create(int maxLines) {
        return new LogBufferImpl(maxLines);
    }

    boolean isEnabled();

    /**
     * Enables or disables logging. When disabled, new log entries are discarded.
     */
    void setEnabled(boolean on);

    /**
     * Clears all buffered log lines.
     */
    void clear();

    /**
     * Returns complete log text with newline separators.
     */
    String text();

    /**
     * Invoked after log additions and clear(); null removes listener.
     */
    void setListener(Runnable onChange);

    /**
     * Returns the sequence number of the oldest buffered line.
     * Sequence numbers are monotonic across evictions; the range
     * [firstSeq(), nextSeq()) enumerates buffered lines.
     */
    long firstSeq();

    /**
     * Returns the sequence number that will be assigned to the next appended line.
     */
    long nextSeq();

    /**
     * Returns buffered lines with sequence number >= seq, oldest first.
     * If seq <= firstSeq(), returns all lines; if seq >= nextSeq(), returns empty.
     */
    List<String> linesFrom(long seq);

    /**
     * Registers a delta listener for incremental UI updates. The legacy
     * setListener(Runnable) remains supported. Null removes the listener.
     */
    void setDeltaListener(Delta d);

    /**
     * Incremental update callbacks invoked on the calling thread; post to main for UI.
     * Sequence ranges are half-open [from, to).
     */
    interface Delta {
        /** Lines with seq in [from, to) were appended. */
        void onAppended(long from, long to);
        /** Lines with seq < newFirstSeq were evicted; drop leading items. */
        void onTrimmed(long newFirstSeq);
        /** The line at seq was replaced (registered=true dedupe). */
        void onReplaced(long seq);
        /** Buffer was cleared; drop all items. */
        void onCleared();
    }

    /**
     * Populates buffer with stored log string, evicting oldest lines to match maxLines capacity.
     */
    void load(String saved);

    /**
     * Persistence callback invoked after log additions or clear().
     */
    void setPersist(Runnable persist);
}
