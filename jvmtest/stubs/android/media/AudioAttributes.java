package android.media;

public class AudioAttributes {
    public static final int USAGE_VOICE_COMMUNICATION = 2;
    public static final int CONTENT_TYPE_SPEECH = 1;

    public static class Builder {
        public Builder setUsage(int u) { return this; }

        public Builder setContentType(int c) { return this; }

        public AudioAttributes build() { return new AudioAttributes(); }
    }
}
