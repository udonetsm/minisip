package ru.minisip.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import java.util.Arrays;

/** Микрофон и динамик разговорного тракта (в ухо, с эхоподавлением системы). */
final class AndroidAudio implements Audio {

    private static final int RATE = 8000;

    private final AudioManager am;
    private AudioRecord rec;
    private AudioTrack trk;
    private AudioFocusRequest focusReq;
    private int prevMode = -1;
    private volatile boolean speaker;        // выбранный вывод: громкая связь или в ухо
    private volatile boolean muted;          // заглушен ли микрофон

    AndroidAudio(Context ctx) {
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public boolean start() {
        try {
            prevMode = am.getMode();
            requestFocus();
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            route();

            int rb = AudioRecord.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufRec = Math.max(rb, 3200) * 2;

            AudioRecord r = null;
            for (int i = 0; i < 5; i++) {
                try {
                    r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            bufRec);
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) break;
                    r.release();
                    r = null;
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            if (r == null) {
                stop();
                return false;
            }
            rec = r;

            int tb = AudioTrack.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufTrk = Math.max(tb, 1600) * 2;

            AudioTrack t = null;
            for (int i = 0; i < 5; i++) {
                try {
                    t = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build())
                            .setBufferSizeInBytes(bufTrk)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    if (t.getState() == AudioTrack.STATE_INITIALIZED) break;
                    t.release();
                    t = null;
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            if (t == null) {
                stop();
                return false;
            }
            trk = t;

            boolean started = false;
            for (int i = 0; i < 5; i++) {
                try {
                    rec.startRecording();
                    if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        started = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            if (!started) {
                stop();
                return false;
            }

            trk.play();
            return true;
        } catch (RuntimeException e) {   // нет разрешения, устройство занято и т.п.
            stop();
            return false;
        }
    }

    @Override
    public void setSpeaker(boolean on) {
        speaker = on;
        if (prevMode >= 0) route();           // разговор уже идёт: переключаем на лету
    }

    @Override
    public void setMute(boolean mute) {
        muted = mute;
        try {
            am.setMicrophoneMute(mute);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isMuted() {
        return muted;
    }

    /**
     * Android 12+: штатный выбор устройства связи (разговорный динамик или громкая связь).
     * Старее, и если нужного устройства нет (планшет без разговорного динамика), — старый переключатель.
     * Версию Android читаем в момент вызова, так что после обновления системы сама берётся новая ветка.
     */
    @SuppressWarnings("deprecation")
    private void route() {
        if (Build.VERSION.SDK_INT >= 31) {
            int want = speaker ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == want && am.setCommunicationDevice(d)) return;
            }
        }
        am.setSpeakerphoneOn(speaker);
    }

    @Override
    public int read(short[] buf) {
        try {
            int n = rec.read(buf, 0, buf.length);
            if (muted && n > 0) {
                Arrays.fill(buf, 0, n, (short) 0);
            }
            return n;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    public void write(short[] buf, int n) {
        try {
            trk.write(buf, 0, n);
        } catch (RuntimeException e) {
            // трек уже остановлен
        }
    }

    @Override
    public void stop() {
        try {
            am.setMicrophoneMute(false);
        } catch (Exception ignored) {
        }
        try {
            if (rec != null) {
                rec.stop();
                rec.release();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (trk != null) {
                trk.stop();
                trk.release();
            }
        } catch (RuntimeException ignored) {
        }
        rec = null;
        trk = null;
        abandonFocus();
        if (prevMode >= 0) {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else am.setSpeakerphoneOn(false);
            am.setMode(prevMode);
            prevMode = -1;
        }
    }

    @SuppressWarnings("deprecation")
    private synchronized void requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(focus -> {})
                        .build();
                am.requestAudioFocus(focusReq);
            }
        } else {
            am.requestAudioFocus(focus -> {}, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq != null) {
                am.abandonAudioFocusRequest(focusReq);
                focusReq = null;
            }
        } else {
            am.abandonAudioFocus(focus -> {});
        }
    }
}
