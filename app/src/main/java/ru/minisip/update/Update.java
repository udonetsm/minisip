package ru.minisip.update;

import android.content.Context;

import java.io.File;

import ru.minisip.log.LogSink;

/**
 * Update manager interface for GitHub Releases update checks and installations.
 */
public interface Update {

    static Update create(Context ctx) {
        return new UpdateImpl(ctx);
    }

    /**
     * Sets update channel ("stable" or "prerelease"). Saved in preferences.
     */
    void setChannel(String channel);

    String channel();

    /**
     * Sets persistent update listener.
     */
    void setListener(Listener l);

    /**
     * Checks selected update channel for new releases. Auto-downloads if update is available.
     */
    void check(Listener l);

    /**
     * Downloads latest release APK to cache directory with progress.
     */
    void download(Listener l);

    /**
     * Launches system APK installer for downloaded release file.
     */
    void install();

    /**
     * Sets log sink for update events.
     */
    void setLogSink(LogSink s);

    interface Listener {
        /**
         * Update check completed.
         */
        default void onChecked(Result r) {
        }

        /**
         * Download progress percentage (0..100).
         */
        default void onProgress(int percent) {
        }

        /**
         * APK download completed.
         */
        default void onDownloaded(File apk) {
        }

        /**
         * Error handler with displayable error message.
         */
        default void onError(String reason) {
        }
    }

    /**
     * Release check result model.
     */
    final class Result {
        public boolean hasUpdate;
        public long releaseMs;
        public String tag;
        public String changelog;
        /**
         * Stable channel empty (404): offer pre-release option.
         */
        public boolean noStable;
    }
}
