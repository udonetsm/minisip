package ru.minisip.update;

/** Константы пакета: URL каналов, ключ prefs, HTTP-параметры. */
final class UpdateConfig {
    private UpdateConfig() {}

    static final String STABLE = "https://api.github.com/repos/udonetsm/minisip/releases/latest";
    static final String PRERELEASE = "https://api.github.com/repos/udonetsm/minisip/releases";

    static final String PREFS = "app";
    static final String KEY_CHANNEL = "updateChannel";
    static final String CH_STABLE = "stable";
    static final String CH_PRERELEASE = "prerelease";

    static final String USER_AGENT = "MiniSIP-Updater";
    static final int TIMEOUT_MS = 15000;

    /** Совпадает с android:authorities FileProvider в манифесте: "${applicationId}.fileprovider". */
    static final String AUTHORITY_SUFFIX = ".fileprovider";
}
