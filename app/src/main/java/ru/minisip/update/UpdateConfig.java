package ru.minisip.update;

/**
 * Configuration constants for update checking and installation.
 */
final class UpdateConfig {
    static final String STABLE = "https://api.github.com/repos/udonetsm/minisip/releases/latest";
    static final String PRERELEASE = "https://api.github.com/repos/udonetsm/minisip/releases";
    static final String PREFS = "app";
    static final String KEY_CHANNEL = "updateChannel";
    static final String CH_STABLE = "stable";
    static final String CH_PRERELEASE = "prerelease";
    static final String USER_AGENT = "MiniSIP-Updater";
    static final int TIMEOUT_MS = 15000;
    /**
     * FileProvider authority suffix matching AndroidManifest configuration.
     */
    static final String AUTHORITY_SUFFIX = ".fileprovider";

    private UpdateConfig() {
    }
}
