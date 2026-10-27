package ru.minisip.app;

/**
 * Pure Java policy deciding whether the log view follows new lines.
 * Thresholds are expressed in visual text lines (not log records):
 * one visual line equals one line height of the log text field.
 * No Android dependencies; fully unit-testable on the JVM.
 */
public final class FollowPolicy {

    /** Enter DETACHED when at least this many visual lines are hidden below. */
    public static final double DETACH_LINES = 3.0;

    /** Return to FOLLOWING when at most this many visual lines are hidden below. */
    public static final double FOLLOW_LINES = 0.1;

    private boolean following = true;

    public boolean isFollowing() {
        return following;
    }

    /**
     * Called when the user scrolls manually. Programmatic scrolling and
     * content appends must not call this method.
     *
     * @param hiddenBelowLines visual lines hidden below the viewport bottom
     *                         (px below / lineHeight)
     */
    public void onUserScroll(double hiddenBelowLines) {
        if (following) {
            if (hiddenBelowLines >= DETACH_LINES) following = false;
        } else {
            if (hiddenBelowLines <= FOLLOW_LINES) following = true;
        }
    }

    /** Called when the user taps the "N new" badge; forces FOLLOWING. */
    public void onFollowTap() {
        following = true;
    }

    /** Called on clear() or rebind; resets to FOLLOWING. */
    public void reset() {
        following = true;
    }
}
