package ru.minisip.log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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
    private volatile Delta delta;
    private long firstSeq = 0;

    LogBufferImpl(int maxLines) {
        this.max = Math.max(1, maxLines);
    }

    @Override
    public void log(String line) {
        if (!enabled || line == null) return;
        long appendedFrom = -1, appendedTo = -1;
        long trimmedTo = -1;
        long replacedSeq = -1;
        synchronized (this) {
            String formatted = time.format(new Date()) + " " + line;
            boolean isRegTrue = line.contains("registered=true");
            if (isRegTrue && !lines.isEmpty()) {
                String lastLine = lines.peekLast();
                if (lastLine != null && lastLine.contains("registered=true")) {
                    lines.removeLast();
                    replacedSeq = firstSeq + lines.size();
                }
            }
            if (replacedSeq >= 0) {
                lines.addLast(formatted);
            } else {
                if (lines.size() >= max) {
                    lines.removeFirst();
                    firstSeq++;
                    trimmedTo = firstSeq;
                }
                appendedFrom = firstSeq + lines.size();
                lines.addLast(formatted);
                appendedTo = firstSeq + lines.size();
            }
        }
        if (trimmedTo >= 0) notifyTrimmed(trimmedTo);
        if (replacedSeq >= 0) notifyReplaced(replacedSeq);
        if (appendedFrom >= 0) notifyAppended(appendedFrom, appendedTo);
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
            firstSeq += lines.size();
            lines.clear();
        }
        notifyCleared();
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
    public synchronized long firstSeq() {
        return firstSeq;
    }

    @Override
    public synchronized long nextSeq() {
        return firstSeq + lines.size();
    }

    @Override
    public synchronized List<String> linesFrom(long seq) {
        if (seq >= firstSeq + lines.size()) return Collections.emptyList();
        if (seq <= firstSeq) seq = firstSeq;
        int skip = (int) (seq - firstSeq);
        List<String> out = new ArrayList<>(lines.size() - skip);
        int i = 0;
        for (String s : lines) {
            if (i++ >= skip) out.add(s);
        }
        return out;
    }

    @Override
    public void setDeltaListener(Delta d) {
        delta = d;
    }

    @Override
    public void load(String saved) {
        if (saved == null || saved.isEmpty()) return;
        long from;
        long to;
        synchronized (this) {
            lines.clear();
            firstSeq = 0;
            String[] arr = saved.split("\n", -1);
            int start = Math.max(0, arr.length - max);
            for (int i = start; i < arr.length; i++) {
                if (!arr[i].isEmpty()) lines.addLast(arr[i]);
            }
            from = firstSeq;
            to = firstSeq + lines.size();
        }
        notifyCleared();
        if (to > from) notifyAppended(from, to);
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

    private void notifyAppended(long from, long to) {
        Delta d = delta;
        if (d != null) d.onAppended(from, to);
    }

    private void notifyTrimmed(long newFirst) {
        Delta d = delta;
        if (d != null) d.onTrimmed(newFirst);
    }

    private void notifyReplaced(long seq) {
        Delta d = delta;
        if (d != null) d.onReplaced(seq);
    }

    private void notifyCleared() {
        Delta d = delta;
        if (d != null) d.onCleared();
    }
}