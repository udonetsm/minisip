package android.media;

public class AudioTrack {
    public static final int STATE_INITIALIZED = 1;
    public static final int MODE_STREAM = 1;

    public int getState() { return STATE_INITIALIZED; }
    public int play() { return 0; }
    public int write(short[] audioData, int offsetInBytes, int sizeInBytes) { return sizeInBytes; }
    public void stop() {}
    public void release() {}

    public static class Builder {
        public Builder setAudioAttributes(AudioAttributes attr) { return this; }
        public Builder setAudioFormat(AudioFormat fmt) { return this; }
        public Builder setBufferSizeInBytes(int size) { return this; }
        public Builder setTransferMode(int mode) { return this; }
        public AudioTrack build() { return new AudioTrack(); }
    }

    public static int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        return 1024;
    }
}
