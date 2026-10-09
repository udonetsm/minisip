package android.media;

public class AudioManager {
    public static final int MODE_IN_COMMUNICATION = 3;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT = 2;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4;
    public static final int STREAM_VOICE_CALL = 0;

    public interface OnAudioFocusChangeListener {
        void onAudioFocusChange(int focusChange);
    }

    private int mode = 0;
    private boolean micMuted;
    private boolean speakerphoneOn;

    public int getMode() { return mode; }
    public void setMode(int mode) { this.mode = mode; }
    public void setMicrophoneMute(boolean on) { this.micMuted = on; }
    public void setSpeakerphoneOn(boolean on) { this.speakerphoneOn = on; }
    public AudioDeviceInfo[] getAvailableCommunicationDevices() { return new AudioDeviceInfo[0]; }
    public boolean setCommunicationDevice(AudioDeviceInfo device) { return true; }
    public void clearCommunicationDevice() {}
    public int requestAudioFocus(AudioFocusRequest request) { return 1; }
    public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType, int durationHint) { return 1; }
    public int abandonAudioFocusRequest(AudioFocusRequest request) { return 1; }
    public int abandonAudioFocus(OnAudioFocusChangeListener l) { return 1; }
}
