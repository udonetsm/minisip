package android.media;

public class AudioTrack {
    public static final int MODE_STREAM = 1;

    public static int getMinBufferSize(int rate, int ch, int enc) { return 640; }

    public void play() {}

    public int write(short[] b, int off, int len) { return len; }

    public void stop() {}

    public void release() {}

    public static class Builder {
        public Builder setAudioAttributes(AudioAttributes a) { return this; }

        public Builder setAudioFormat(AudioFormat f) { return this; }

        public Builder setBufferSizeInBytes(int n) { return this; }

        public Builder setTransferMode(int m) { return this; }

        public AudioTrack build() { return new AudioTrack(); }
    }
}
