package ru.minisip.log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Реализация LogBuffer: ArrayDeque под замком, строки с меткой времени. */
final class LogBufferImpl implements LogBuffer {

    private final int max;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private volatile boolean enabled = true;
    private volatile Runnable listener;

    LogBufferImpl(int maxLines) {
        this.max = Math.max(1, maxLines);
    }

    @Override
    public void log(String line) {
        if (!enabled || line == null) return;
        synchronized (this) {
            if (lines.size() >= max) lines.removeFirst();
            lines.addLast(time.format(new Date()) + " " + line);
        }
        notifyChanged();
    }

    @Override
    public void setEnabled(boolean on) {
        enabled = on;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void clear() {
        synchronized (this) {
            lines.clear();
        }
        notifyChanged();
    }

    @Override
    public synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    @Override
    public void setListener(Runnable onChange) {
        listener = onChange;
    }

    private void notifyChanged() {
        Runnable r = listener;
        if (r != null) r.run();
    }
}