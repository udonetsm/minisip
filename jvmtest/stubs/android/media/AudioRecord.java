package android.media;

public class AudioRecord {
    public static final int STATE_INITIALIZED = 1;
    public static final int RECORDSTATE_RECORDING = 3;

    public int getState() { return STATE_INITIALIZED; }
    public int getRecordingState() { return RECORDSTATE_RECORDING; }
    public void startRecording() {}
    public int read(short[] audioData, int offsetInBytes, int sizeInBytes) {
        try { Thread.sleep(20); } catch (Exception e) {}
        return sizeInBytes;
    }
    public void stop() {}
    public void release() {}

    public AudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes) {}

    public static int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        return 1024;
    }
}
