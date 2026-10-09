package android.os;

public class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a;
    public static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;
    public static final int RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 1;

    public boolean isWakeLockLevelSupported(int level) { return true; }
    public boolean isInteractive() { return true; }
    public WakeLock newWakeLock(int level, String tag) { return new WakeLock(); }

    public static class WakeLock {
        public void acquire() {}
        public void acquire(long timeout) {}
        public void release() {}
        public void release(int flags) {}
        public boolean isHeld() { return false; }
        public void setReferenceCounted(boolean value) {}
    }
}
