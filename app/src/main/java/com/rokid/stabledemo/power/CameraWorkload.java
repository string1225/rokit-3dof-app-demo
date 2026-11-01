package com.rokid.stabledemo.power;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Offscreen Camera2 workload. Every callback belongs to this one stage instance. */
final class CameraWorkload implements BenchmarkWorkload {
    private final Context context;
    private final File directory;
    private final boolean video;
    private final HandlerThread thread = new HandlerThread("power-camera");
    private final Handler handler;
    private final List<Stopped> stopCallbacks = new ArrayList<>();
    private Callback callback;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader jpeg;
    private ImageReader offscreen;
    private MediaRecorder recorder;
    private Surface recorderSurface;
    private Size size;
    private Range<Integer> fpsRange;
    private int fps = 30;
    private boolean opening;
    private boolean closing;
    private volatile boolean stopDone;
    private boolean cameraClosed;
    private boolean recording;
    private boolean announced;
    private boolean failed;
    private boolean photoInFlight;
    private long photoRequestedAt;
    private long lastFrame;
    private int photos;
    private String cameraId;
    private volatile String stopError;
    private File videoFile;

    CameraWorkload(Context context, File directory, boolean video, Handler ignored) {
        this.context = context;
        this.directory = directory;
        this.video = video;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override public void start(Callback callback) {
        this.callback = callback;
        handler.post(this::open);
    }

    @SuppressLint("MissingPermission") // Caller obtains CAMERA permission before starting.
    private void open() {
        if (closing) return;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) throw new IOException("CameraManager unavailable");
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) throw new IOException("No camera is exposed by this device");
            cameraId = ids[0];
            for (String id : ids) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; break; }
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streams == null) throw new IOException("Camera stream configuration unavailable");
            if (video) {
                fpsRange = chooseFps(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
                fps = fpsRange == null ? 30 : Math.min(30, fpsRange.getUpper());
                size = chooseSize(streams.getOutputSizes(MediaRecorder.class), true, fps);
                prepareVideo();
                // A drained YUV consumer supplies the offscreen preview required by some devices.
                Size preview = chooseSize(streams.getOutputSizes(ImageFormat.YUV_420_888), false, 0);
                offscreen = ImageReader.newInstance(preview.getWidth(), preview.getHeight(), ImageFormat.YUV_420_888, 2);
                offscreen.setOnImageAvailableListener(reader -> {
                    try (Image image = reader.acquireLatestImage()) { if (image != null) image.getTimestamp(); }
                    catch (RuntimeException e) { if (!closing) fail("Preview image: " + e); }
                }, handler);
            } else {
                size = chooseSize(streams.getOutputSizes(ImageFormat.JPEG), false, 0);
                jpeg = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
                jpeg.setOnImageAvailableListener(this::savePhoto, handler);
            }
            opening = true;
            manager.openCamera(cameraId, deviceCallback, handler);
            handler.postDelayed(watchdog, 1000);
        } catch (Exception e) { opening = false; fail(e.getClass().getSimpleName() + ": " + e.getMessage()); }
    }

    private void prepareVideo() throws IOException {
        videoFile = new File(directory, "silent-video.mp4");
        recorder = new MediaRecorder(context);
        recorder.setOnErrorListener((mr, what, extra) -> handler.post(() -> {
            if (!closing) fail("MediaRecorder error " + what + "/" + extra);
        }));
        recorder.setOnInfoListener((mr, what, extra) -> handler.post(() -> {
            if (!closing && (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                    || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED))
                fail("Recording ended unexpectedly: info " + what + "/" + extra);
        }));
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(videoFile.getAbsolutePath());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(size.getWidth(), size.getHeight());
        recorder.setVideoFrameRate(fps);
        recorder.setVideoEncodingBitRate(Math.max(1_000_000, Math.min(4_000_000, size.getWidth() * size.getHeight() * 5)));
        recorder.prepare();
        recorderSurface = recorder.getSurface();
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            opening = false;
            device = camera;
            if (closing) { camera.close(); return; }
            try {
                List<Surface> outputs = video ? Arrays.asList(recorderSurface, offscreen.getSurface())
                        : java.util.Collections.singletonList(jpeg.getSurface());
                List<OutputConfiguration> configurations = new ArrayList<>();
                for (Surface surface : outputs) configurations.add(new OutputConfiguration(surface));
                camera.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        configurations, command -> handler.post(command), sessionCallback));
            } catch (Exception e) { fail("Camera session: " + e); }
        }
        @Override public void onDisconnected(CameraDevice camera) {
            opening = false;
            device = camera;
            camera.close();
            if (!closing) fail("Camera disconnected");
        }
        @Override public void onError(CameraDevice camera, int error) {
            opening = false;
            device = camera;
            camera.close();
            if (!closing) fail("Camera error " + error);
        }
        @Override public void onClosed(CameraDevice camera) {
            cameraClosed = true;
            if (closing) {
                completeStop();
                if (stopDone) thread.quitSafely();
            } else fail("Camera closed unexpectedly");
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession configured) {
            if (closing || device == null) { configured.close(); return; }
            session = configured;
            try {
                if (video) {
                    CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    request.addTarget(recorderSurface);
                    request.addTarget(offscreen.getSurface());
                    request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    if (fpsRange != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                    configured.setRepeatingRequest(request.build(), captureCallback, handler);
                    recorder.start();
                    recording = true;
                    lastFrame = SystemClock.elapsedRealtime();
                } else takePhoto();
            } catch (Exception e) { fail("Start camera workload: " + e); }
        }
        @Override public void onConfigureFailed(CameraCaptureSession configured) {
            configured.close();
            if (!closing) fail("Camera rejected the negotiated output session");
        }
        @Override public void onClosed(CameraCaptureSession closed) {
            if (!closing && closed == session) fail("Camera session closed unexpectedly");
        }
    };

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (closing) return;
            lastFrame = SystemClock.elapsedRealtime();
            if (video && recording && !announced) {
                announced = true;
                callback.ready("Camera " + cameraId + ": silent AVC " + size + ", " + fps + " fps; MP4 saved locally");
            }
        }
        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
            if (!closing) fail("Camera capture failed: reason " + failure.getReason());
        }
        @Override public void onCaptureSequenceAborted(CameraCaptureSession s, int sequenceId) {
            if (!closing) fail("Camera capture sequence aborted: " + sequenceId);
        }
    };

    private final Runnable photoTask = this::takePhoto;
    private void takePhoto() {
        if (closing || failed) return;
        if (photoInFlight) { fail("Photo capture overlapped its previous request"); return; }
        try {
            if (device == null || session == null || jpeg == null) throw new IOException("Photo camera not configured");
            photoInFlight = true;
            photoRequestedAt = SystemClock.elapsedRealtime();
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(jpeg.getSurface());
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
            session.capture(request.build(), captureCallback, handler);
        } catch (Exception e) { photoInFlight = false; fail("Photo request: " + e); }
    }

    private void savePhoto(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null) return;
            if (closing) return;
            ByteBuffer bytes = image.getPlanes()[0].getBuffer();
            if (!bytes.hasRemaining()) throw new IOException("Empty JPEG image");
            File file = new File(directory, String.format(java.util.Locale.US, "photo-%03d-%d.jpg", ++photos, System.currentTimeMillis()));
            File temporary = new File(directory, file.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] chunk = new byte[Math.min(8192, bytes.remaining())];
                while (bytes.hasRemaining()) {
                    int count = Math.min(chunk.length, bytes.remaining());
                    bytes.get(chunk, 0, count);
                    output.write(chunk, 0, count);
                }
                output.getFD().sync();
            }
            if (!temporary.renameTo(file)) throw new IOException("Cannot publish completed JPEG " + file.getName());
            photoInFlight = false;
            if (!announced) {
                announced = true;
                callback.ready("Camera " + cameraId + ": JPEG " + size + "; first photo saved; every 10 s");
            }
            // Absolute request cadence; slow captures never create a queue of simultaneous requests.
            long next = photoRequestedAt + PhaseSchedule.PHOTO_INTERVAL_MS;
            handler.postDelayed(photoTask, Math.max(0, next - SystemClock.elapsedRealtime()));
        } catch (Exception e) { photoInFlight = false; if (!closing) fail("Photo save: " + e); }
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (closing || failed) return;
            long now = SystemClock.elapsedRealtime();
            if (video && announced && now - lastFrame > 10_000) fail("Video camera supplied no frames for 10 s");
            if (!video && photoInFlight && now - photoRequestedAt > 15_000) fail("Photo capture timed out after 15 s");
            handler.postDelayed(this, 1000);
        }
    };

    private void fail(String reason) {
        if (closing || failed) return;
        failed = true;
        callback.failed(reason);
    }

    @Override public void stop(Stopped callback) {
        if (stopDone) { callback.stopped(stopError); return; }
        boolean queued = handler.post(() -> {
            if (stopDone) { callback.stopped(stopError); return; }
            stopCallbacks.add(callback);
            if (closing) return;
            closing = true;
            handler.removeCallbacks(photoTask);
            handler.removeCallbacks(watchdog);
            if (session != null) {
                try { session.stopRepeating(); session.abortCaptures(); }
                catch (CameraAccessException | IllegalStateException e) { addStopError("Stop capture: " + e); }
            }
            if (recorder != null && recording) {
                try { recorder.stop(); }
                catch (RuntimeException e) { addStopError("MP4 finalization failed: " + e); }
                recording = false;
            }
            if (session != null) { session.close(); session = null; }
            if (device != null) device.close();
            if ((device == null && !opening) || cameraClosed) completeStop();
            else handler.postDelayed(() -> {
                if (!stopDone) {
                    addStopError("Camera close callback timed out after 3 s");
                    completeStop();
                }
            }, 3000);
        });
        if (!queued) callback.stopped(stopError == null ? "Camera callback thread unavailable during stop" : stopError);
    }

    private void completeStop() {
        if (stopDone) return;
        stopDone = true;
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException e) { addStopError("Recorder reset: " + e); }
            try { recorder.release(); } catch (RuntimeException e) { addStopError("Recorder release: " + e); }
            recorder = null;
        }
        if (recorderSurface != null) {
            try { recorderSurface.release(); } catch (RuntimeException e) { addStopError("Recorder surface release: " + e); }
            recorderSurface = null;
        }
        if (jpeg != null) {
            try { jpeg.close(); } catch (RuntimeException e) { addStopError("JPEG reader release: " + e); }
            jpeg = null;
        }
        if (offscreen != null) {
            try { offscreen.close(); } catch (RuntimeException e) { addStopError("Preview reader release: " + e); }
            offscreen = null;
        }
        if (videoFile != null && announced && videoFile.length() == 0) addStopError("Recorded MP4 is empty");
        for (Stopped callback : new ArrayList<>(stopCallbacks)) callback.stopped(stopError);
        stopCallbacks.clear();
        // A pending open must retain its callback thread so a late onOpened can close the device.
        if (!opening) thread.quitSafely();
    }

    private void addStopError(String error) { stopError = stopError == null ? error : stopError + "; " + error; }

    private static Range<Integer> chooseFps(Range<Integer>[] ranges) {
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = null;
        int score = Integer.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            if (range.getLower() > 30 || range.getUpper() <= 0) continue;
            int candidate = Math.abs(Math.min(30, range.getUpper()) - 30) * 100
                    + Math.abs(range.getUpper() - 30) + Math.abs(range.getLower() - 30);
            if (candidate < score) { score = candidate; best = range; }
        }
        return best;
    }

    private static Size chooseSize(Size[] available, boolean requireEncoder, int fps) throws IOException {
        if (available == null || available.length == 0) throw new IOException("No supported camera output sizes");
        List<Size> choices = new ArrayList<>(Arrays.asList(available));
        choices.sort(Comparator.comparingDouble(CameraWorkload::sizeScore));
        for (Size size : choices) {
            if (size.getWidth() <= 0 || size.getHeight() <= 0) continue;
            if (!requireEncoder || encoderSupports(size, fps)) return size;
        }
        throw new IOException("No camera size supported by an AVC encoder");
    }
    private static double sizeScore(Size size) {
        long area = (long) size.getWidth() * size.getHeight();
        double safePenalty = area > 1280L * 720 ? 10_000_000 : 0;
        return safePenalty + Math.abs(area - 640L * 480)
                + Math.abs(size.getWidth() / (double) size.getHeight() - 4.0 / 3.0) * 100_000;
    }
    private static boolean encoderSupports(Size size, int fps) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.getWidth(), size.getHeight());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_000_000, Math.min(4_000_000, size.getWidth() * size.getHeight() * 5)));
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        try { return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null; }
        catch (RuntimeException e) { return false; }
    }
}
