package android.media;

public class AudioRecord {
    public static final int STATE_INITIALIZED = 1;

    public static int getMinBufferSize(int rate, int ch, int enc) { return 640; }

    public AudioRecord(int src, int rate, int ch, int enc, int size) {}

    public int getState() { return STATE_INITIALIZED; }

    public void startRecording() {}

    public int read(short[] b, int off, int len) { return len; }

    public void stop() {}

    public void release() {}
}
