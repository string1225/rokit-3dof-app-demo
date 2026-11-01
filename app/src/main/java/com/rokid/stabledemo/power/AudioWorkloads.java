package com.rokid.stabledemo.power;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioWorkloads {
    private AudioWorkloads() { }

    /** Records to a disposable in-memory buffer. No PCM is written or retained. */
    static final class Microphone implements BenchmarkWorkload {
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile AudioRecord record;
        private Thread reader;
        private final Object stopLock = new Object();
        private final List<Stopped> stoppedCallbacks = new ArrayList<>();
        private boolean stopStarted;
        private boolean stopDone;
        private String stopError;
        private volatile String releaseError;

        @Override public void start(Callback callback) {
            reader = new Thread(() -> run(callback), "power-mic-discard");
            reader.start();
        }

        @SuppressLint("MissingPermission") // Caller requests RECORD_AUDIO before starting a benchmark.
        private void run(Callback callback) {
            AudioRecord active = null;
            String config = "";
            StringBuilder errors = new StringBuilder();
            try {
                int[] masks = {AudioFormat.CHANNEL_IN_MONO, 0x6000FC};
                for (int mask : masks) {
                    if (closed.get()) return;
                    AudioRecord candidate = null;
                    try {
                        int channels = mask == AudioFormat.CHANNEL_IN_MONO ? 1 : 8;
                        int minimum = AudioRecord.getMinBufferSize(16_000, mask, AudioFormat.ENCODING_PCM_16BIT);
                        int buffer = Math.max(minimum, 16_000 * channels * 2 / 4);
                        candidate = new AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, mask,
                                AudioFormat.ENCODING_PCM_16BIT, buffer);
                        if (candidate.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord uninitialized");
                        record = candidate;
                        if (closed.get()) { candidate.release(); record = null; return; }
                        candidate.startRecording();
                        if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                            throw new IllegalStateException("Microphone did not start recording");
                        active = candidate;
                        config = "PCM16 16000 Hz, " + channels + " channel(s); PCM discarded";
                        break;
                    } catch (RuntimeException e) {
                        errors.append(mask).append(": ").append(e.getMessage()).append("; ");
                        if (candidate != null) try { candidate.release(); } catch (RuntimeException ignored) { }
                        record = null;
                    }
                }
                if (active == null) throw new IllegalStateException("No microphone configuration worked: " + errors);
                short[] discarded = new short[4096];
                boolean announced = false;
                long lastData = SystemClock.elapsedRealtime();
                while (!closed.get()) {
                    int count = active.read(discarded, 0, discarded.length, AudioRecord.READ_BLOCKING);
                    if (count < 0 && !closed.get()) throw new IllegalStateException("AudioRecord.read returned " + count);
                    if (count > 0) {
                        lastData = SystemClock.elapsedRealtime();
                        if (!announced && !closed.get()) { announced = true; callback.ready(config); }
                    } else if (!closed.get()) {
                        if (SystemClock.elapsedRealtime() - lastData > 5000) throw new IllegalStateException("Microphone supplied no PCM for 5 s");
                        Thread.sleep(10);
                    }
                }
            } catch (Exception e) {
                if (!closed.get()) callback.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                AudioRecord closing = active == null ? record : active;
                if (closing != null) {
                    try { closing.stop(); } catch (RuntimeException ignored) { }
                    try { closing.release(); } catch (RuntimeException e) { releaseError = "Microphone release: " + e; }
                }
                record = null;
            }
        }

        @Override public void stop(Stopped callback) {
            synchronized (stopLock) {
                if (stopDone) { callback.stopped(stopError); return; }
                stoppedCallbacks.add(callback);
                if (stopStarted) return;
                stopStarted = true;
            }
            closed.set(true);
            new Thread(() -> {
                String error = null;
                AudioRecord active = record;
                if (active != null) try { active.stop(); } catch (RuntimeException ignored) { }
                try {
                    if (reader != null) reader.join(3000);
                    if (reader != null && reader.isAlive()) error = "Microphone reader did not stop within 3 s";
                    if (releaseError != null) error = error == null ? releaseError : error + "; " + releaseError;
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); error = "Interrupted stopping microphone"; }
                List<Stopped> callbacks;
                synchronized (stopLock) {
                    stopDone = true;
                    stopError = error;
                    callbacks = new ArrayList<>(stoppedCallbacks);
                    stoppedCallbacks.clear();
                }
                for (Stopped item : callbacks) item.stopped(error);
            }, "power-mic-stop").start();
        }
    }

    static final class Speaker implements BenchmarkWorkload {
        private final Context context;
        private AudioTrack track;
        private boolean closed;
        private String stopError;
        Speaker(Context context) { this.context = context; }

        @Override public synchronized void start(Callback callback) {
            if (closed) return;
            try {
                AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (manager == null) throw new IllegalStateException("AudioManager unavailable");
                int volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (volume == 0 || manager.isStreamMute(AudioManager.STREAM_MUSIC))
                    throw new IllegalStateException("Music volume is muted/zero; sound workload cannot run at the preserved volume");
                final int rate = 16_000;
                short[] loop = new short[rate];
                for (int i = 0; i < loop.length; i++) loop[i] = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 4096);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(loop.length * 2).build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED && track.getState() != AudioTrack.STATE_NO_STATIC_DATA)
                    throw new IllegalStateException("AudioTrack initialization failed");
                if (track.write(loop, 0, loop.length, AudioTrack.WRITE_BLOCKING) != loop.length)
                    throw new IllegalStateException("Incomplete audio loop write");
                if (track.setLoopPoints(0, loop.length, -1) != AudioTrack.SUCCESS)
                    throw new IllegalStateException("Audio loop configuration failed");
                track.play();
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) throw new IllegalStateException("AudioTrack did not start");
                callback.ready("440 Hz PCM16 loop; volume " + volume + "/" + manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + " preserved");
            } catch (RuntimeException e) {
                callback.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Override public synchronized void stop(Stopped callback) {
            if (!closed) {
                closed = true;
                if (track != null) {
                    try { track.stop(); } catch (RuntimeException e) { stopError = e.toString(); }
                    try { track.release(); } catch (RuntimeException e) { stopError = "AudioTrack release: " + e; }
                    track = null;
                }
            }
            callback.stopped(stopError);
        }

        @Override public synchronized String healthFailure() {
            if (closed) return null;
            if (track == null || track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) return "Sound playback stopped unexpectedly";
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || audio.isStreamMute(AudioManager.STREAM_MUSIC))
                return "Music volume became muted/zero during the sound workload";
            return null;
        }
    }
}
