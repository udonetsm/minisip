package android.media;

public class AudioFocusRequest {
    public static class Builder {
        public Builder(int focusGain) {}
        public Builder setAudioAttributes(AudioAttributes attributes) { return this; }
        public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener l) { return this; }
        public Builder setAcceptsDelayedFocusGain(boolean accept) { return this; }
        public AudioFocusRequest build() { return new AudioFocusRequest(); }
    }
}
