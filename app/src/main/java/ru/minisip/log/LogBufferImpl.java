package ru.minisip.log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Thread-safe implementation of LogBuffer backed by ArrayDeque and synchronized locks.
 */
final class LogBufferImpl implements LogBuffer {

    private final int max;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private volatile boolean enabled = true;
    private volatile Runnable listener;
    private volatile Runnable persist;

    LogBufferImpl(int maxLines) {
        this.max = Math.max(1, maxLines);
    }

    @Override
    public void log(String line) {
        if (!enabled || line == null) return;
        synchronized (this) {
            String formatted = time.format(new Date()) + " " + line;
            boolean isRegTrue = line.contains("registered=true");
            if (isRegTrue && !lines.isEmpty()) {
                String lastLine = lines.peekLast();
                if (lastLine != null && lastLine.contains("registered=true")) {
                    lines.removeLast();
                }
            }
            if (lines.size() >= max) lines.removeFirst();
            lines.addLast(formatted);
        }
        notifyChanged();
        notifyPersist();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean on) {
        enabled = on;
    }

    @Override
    public void clear() {
        synchronized (this) {
            lines.clear();
        }
        notifyChanged();
        notifyPersist();
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

    @Override
    public void load(String saved) {
        if (saved == null || saved.isEmpty()) return;
        synchronized (this) {
            lines.clear();
            String[] arr = saved.split("\n", -1);
            int from = Math.max(0, arr.length - max);
            for (int i = from; i < arr.length; i++) {
                if (!arr[i].isEmpty()) lines.addLast(arr[i]);
            }
        }
    }

    @Override
    public void setPersist(Runnable p) {
        persist = p;
    }

    private void notifyChanged() {
        Runnable r = listener;
        if (r != null) r.run();
    }

    private void notifyPersist() {
        Runnable r = persist;
        if (r != null) r.run();
    }
}
