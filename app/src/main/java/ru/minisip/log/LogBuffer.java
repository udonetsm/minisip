package ru.minisip.log;

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
     * Populates buffer with stored log string, evicting oldest lines to match maxLines capacity.
     */
    void load(String saved);

    /**
     * Persistence callback invoked after log additions or clear().
     */
    void setPersist(Runnable persist);
}
