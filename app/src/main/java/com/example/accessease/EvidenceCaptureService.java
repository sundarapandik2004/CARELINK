package com.example.accessease;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EvidenceCaptureService — Crash-proof evidence capture using Camera2 API.
 *
 * Uses Camera2 directly instead of CameraX to avoid LifecycleOwner
 * conflicts that cause crashes in Services on Android 14.
 *
 * Flow:
 *   1. SOS pressed → EvidenceCaptureService.start(context)
 *   2. Opens back camera via Camera2
 *   3. Takes photo every 5 seconds (up to 8 photos)
 *   4. Uploads each photo to Cloudinary
 *   5. Saves URL to Firestore → caregiver sees instantly
 *   6. Records 60s audio in parallel
 */
public class EvidenceCaptureService extends Service {

    private static final String TAG      = "EvidenceCapture";
    private static final String CH_ID    = "evidence_capture";
    private static final int    NOTIF_ID = 7001;

    private static final int  AUDIO_DURATION_MS = 60_000;
    private static final int  PHOTO_INTERVAL_MS = 5_000;
    private static final int  MAX_PHOTOS        = 8;

    // ── Camera2 ──────────────────────────────────────────────────────────────
    private CameraDevice       cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader        imageReader;
    private HandlerThread      cameraThread;
    private Handler            cameraHandler;
    private String             cameraId;
    private SurfaceTexture     dummyTexture;
    private Surface            dummySurface;

    // ── Audio ─────────────────────────────────────────────────────────────────
    private MediaRecorder mediaRecorder;
    private String        audioFilePath;

    // ── State ─────────────────────────────────────────────────────────────────
    private Handler  mainHandler;
    private String   userId;
    private String   evidenceDocId;
    private int      photoCount   = 0;
    private boolean  isCapturing  = false;
    private ExecutorService uploadExecutor;
    // Queue for photo URLs that arrived before Firestore doc was created
    private final java.util.List<String[]> pendingPhotoUrls =
            new java.util.ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    //  Static entry points
    // ─────────────────────────────────────────────────────────────────────────
    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, EvidenceCaptureService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.e("EvidenceCapture", "start() failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  onCreate
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler   = new Handler(Looper.getMainLooper());
        uploadExecutor = Executors.newFixedThreadPool(3);

        // Start camera background thread
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  onStartCommand
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isCapturing) return START_NOT_STICKY;
        isCapturing = true;
        photoCount = 0;

        try {
            userId = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

            createNotificationChannel();

            // FIX: Do NOT combine CAMERA|MICROPHONE|LOCATION in one startForeground call.
            // On Android 14, if any one permission is denied the combined call throws
            // SecurityException and crashes the service before any capture begins.
            // Use MICROPHONE type only (declared in manifest). Camera2 still works.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIF_ID,
                            buildNotification("🔴 Capturing SOS evidence..."),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } catch (SecurityException se) {
                    Log.w(TAG, "Mic permission missing, starting without type: " + se.getMessage());
                    startForeground(NOTIF_ID, buildNotification("🔴 Capturing SOS evidence..."));
                }
            } else {
                startForeground(NOTIF_ID, buildNotification("🔴 Capturing SOS evidence..."));
            }

            // Create Firestore doc, then start capturing
            createEvidenceDoc();
            startAudioRecording();
            openCamera();

            // Auto-stop after 65 seconds
            mainHandler.postDelayed(this::stopCapture, AUDIO_DURATION_MS + 5000);

        } catch (Exception e) {
            Log.e(TAG, "onStartCommand error: " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firestore — create incident document
    // ─────────────────────────────────────────────────────────────────────────
    private void createEvidenceDoc() {
        if (userId == null) return;
        try {
            long now = System.currentTimeMillis();
            String datetime = new SimpleDateFormat(
                    "dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(new Date(now));

            double lat = 0, lng = 0;
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (lm != null) {
                    @SuppressLint("MissingPermission")
                    Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (loc == null) {
                        @SuppressLint("MissingPermission")
                        Location nl = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        loc = nl;
                    }
                    if (loc != null) { lat = loc.getLatitude(); lng = loc.getLongitude(); }
                }
            } catch (Exception ignored) {}

            Map<String, Object> doc = new HashMap<>();
            doc.put("userId",    userId);
            doc.put("timestamp", now);
            doc.put("datetime",  datetime);
            doc.put("latitude",  lat);
            doc.put("longitude", lng);
            doc.put("status",    "recording");
            doc.put("audioUrl",  "");
            doc.put("photoUrls", new java.util.ArrayList<>());
            doc.put("source",    "sos");

            FirebaseFirestore.getInstance().collection("evidence")
                    .add(doc)
                    .addOnSuccessListener(ref -> {
                        evidenceDocId = ref.getId();
                        Log.d(TAG, "Evidence doc created: " + evidenceDocId);
                        // Now that the doc exists, flush any URLs that were saved
                        // before doc creation completed (race condition protection)
                        flushPendingPhotoUrls();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Doc create failed: " + e.getMessage());
                        // Create a fallback local doc ID so photos are not lost
                        evidenceDocId = "local_" + System.currentTimeMillis();
                    });
        } catch (Exception e) {
            Log.e(TAG, "createEvidenceDoc error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera2 — open camera
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Picks the highest JPEG output size the camera supports.
     * Caps at 4032x3024 (12MP) to keep upload size reasonable for evidence.
     * Falls back to 1920x1080 if map is null.
     */
    private Size chooseBestSize(StreamConfigurationMap map) {
        if (map == null) return new Size(1920, 1080);
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);

        final long MAX_PIXELS = 4032L * 3024L; // ~12MP cap
        Size best = sizes[0];
        long bestPixels = (long) best.getWidth() * best.getHeight();

        for (Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (pixels > bestPixels && pixels <= MAX_PIXELS) {
                best = s;
                bestPixels = pixels;
            }
        }
        return best;
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) return;

            // Find back camera
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
            }
            if (cameraId == null) { Log.e(TAG, "No camera found"); return; }

            // Pick highest JPEG size the camera actually supports.
            // Hardcoding 1920x1080 silently falls back to smallest size if unsupported.
            CameraCharacteristics chars2 = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map2 = chars2.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size captureSize = chooseBestSize(map2);
            Log.d(TAG, "Capture size: " + captureSize.getWidth() + "x" + captureSize.getHeight());
            // 3 buffers: one writing, one processing, one spare — avoids dropped frames
            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(),
                    ImageFormat.JPEG, 3);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    Log.d(TAG, "Camera opened");
                    createCaptureSession();
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close(); cameraDevice = null;
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close(); cameraDevice = null;
                    Log.e(TAG, "Camera error: " + error);
                }
            }, cameraHandler);

        } catch (Exception e) {
            Log.e(TAG, "openCamera error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera2 — create capture session
    // ─────────────────────────────────────────────────────────────────────────
    private void createCaptureSession() {
        try {
            // ROOT FIX: Use a dummy SurfaceTexture for warmup instead of ImageReader.
            // Old code: warmup target = imageReader  → onImageAvailable fired for
            //   every warmup frame → photoCount hit MAX_PHOTOS=8 from warmup frames
            //   before a single real still photo was taken. All 8 "photos" were
            //   dark/blurry warmup frames that appeared as broken images in dashboard.
            // New code: warmup target = dummySurface → ImageReader receives ONLY
            //   frames from explicit captureSession.capture() calls = real photos only.
            dummyTexture = new SurfaceTexture(0);
            dummyTexture.setDefaultBufferSize(320, 240);
            dummySurface = new Surface(dummyTexture);

            cameraDevice.createCaptureSession(
                    Arrays.asList(dummySurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            Log.d(TAG, "Capture session ready");

                            // Warmup: targets dummySurface only.
                            // AF/AE/AWB converge here WITHOUT touching ImageReader.
                            try {
                                CaptureRequest.Builder warmup =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                warmup.addTarget(dummySurface);
                                warmup.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                warmup.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                warmup.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                warmup.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                                session.setRepeatingRequest(warmup.build(), null, cameraHandler);
                                Log.d(TAG, "Sensor warmup started on dummy surface");
                            } catch (Exception we) {
                                Log.w(TAG, "Warmup failed: " + we.getMessage());
                            }

                            // 1800ms warmup then start taking real photos
                            schedulePhoto(1800);
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configure failed");
                        }
                    }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "createCaptureSession error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera2 — schedule and take photo
    // ─────────────────────────────────────────────────────────────────────────
    private void schedulePhoto(long delayMs) {
        if (photoCount >= MAX_PHOTOS) return;
        mainHandler.postDelayed(this::takePhoto, delayMs);
    }

    /**
     * Takes a high-quality still photo using a two-step process:
     *
     * Step 1 — Pre-capture AE/AF trigger (precaptureRequest):
     *   Sends CONTROL_AE_PRECAPTURE_TRIGGER_START + CONTROL_AF_TRIGGER_START.
     *   This tells the camera to lock focus and set correct exposure BEFORE
     *   the actual shutter fires. Without this step the photo is taken while
     *   the camera is still adjusting — causing blur and bad exposure.
     *
     * Step 2 — Still capture (captureRequest):
     *   After 600ms (enough time for AE/AF to converge), fires the actual
     *   shutter with maximum JPEG quality and all enhancement modes ON.
     */
    private void takePhoto() {
        if (captureSession == null || cameraDevice == null || photoCount >= MAX_PHOTOS) return;
        try {
            // ── Stop warmup repeating request before still capture ───────────
            // If repeating request is still running while we fire the still capture,
            // the ImageReader receives warmup frames mixed with the still frame,
            // making it impossible to identify which frame is the actual photo.
            try { captureSession.stopRepeating(); } catch (Exception ignored) {}

            // ── Step 1: Trigger auto-focus + auto-exposure lock ──────────────
            CaptureRequest.Builder preBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            preBuilder.addTarget(imageReader.getSurface());

            // Lock AF
            preBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO);
            preBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START);

            // Lock AE — NO flash (flash causes overexposed white images)
            preBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            preBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            captureSession.capture(preBuilder.build(), null, cameraHandler);

            // ── Step 2: Fire shutter after AE/AF converge (600ms) ────────────
            cameraHandler.postDelayed(() -> {
                if (captureSession == null || cameraDevice == null) return;
                try {
                    CaptureRequest.Builder builder =
                            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    builder.addTarget(imageReader.getSurface());

                    // AF: locked from step 1
                    builder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_AUTO);
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

                    // AE: locked, no flash
                    builder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON);
                    builder.set(CaptureRequest.FLASH_MODE,
                            CaptureRequest.FLASH_MODE_OFF);

                    // AWB: automatic white balance — prevents color cast
                    builder.set(CaptureRequest.CONTROL_AWB_MODE,
                            CaptureRequest.CONTROL_AWB_MODE_AUTO);

                    // JPEG quality 97 — maximum detail for evidence
                    builder.set(CaptureRequest.JPEG_QUALITY, (byte) 97);

                    // Noise reduction: HIGH_QUALITY — removes graininess
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE,
                            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);

                    // Edge enhancement: HIGH_QUALITY — sharper edges, clearer image
                    builder.set(CaptureRequest.EDGE_MODE,
                            CaptureRequest.EDGE_MODE_HIGH_QUALITY);

                    // Lens shading correction: corrects dark corners
                    builder.set(CaptureRequest.SHADING_MODE,
                            CaptureRequest.SHADING_MODE_HIGH_QUALITY);

                    // Color correction: high quality colour rendering
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE,
                            CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY);

                    captureSession.capture(builder.build(),
                            new CameraCaptureSession.CaptureCallback() {
                                @Override
                                public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                                                               @NonNull CaptureRequest req,
                                                               @NonNull TotalCaptureResult res) {
                                    Log.d(TAG, "High-quality photo captured (#" + (photoCount + 1) + ")");
                                }
                            }, cameraHandler);

                } catch (Exception e) {
                    Log.e(TAG, "takePhoto step2 error: " + e.getMessage());
                    schedulePhoto(PHOTO_INTERVAL_MS);
                }
            }, 600); // 600ms for AE/AF to converge

        } catch (Exception e) {
            Log.e(TAG, "takePhoto step1 error: " + e.getMessage());
            schedulePhoto(PHOTO_INTERVAL_MS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera2 — image available callback
    // ─────────────────────────────────────────────────────────────────────────
    private void onImageAvailable(ImageReader reader) {
        // acquireNextImage() is used instead of acquireLatestImage().
        // acquireLatestImage() discards all but the newest frame — on the same
        // cameraHandler thread this means the captured frame is often already
        // gone by the time this callback runs. acquireNextImage() takes the
        // oldest unconsumed frame, which is always the one we just captured.
        Image image = reader.acquireNextImage();
        if (image == null) return;

        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                    .format(new Date());
            File file = new File(getEvidenceDir(), "photo_" + ts + ".jpg");

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();

            // CRITICAL: rewind the buffer to position 0 before reading.
            // Without rewind, buffer.remaining() may return 0 or a partial
            // count if the buffer position was advanced during capture setup,
            // resulting in a 0-byte or corrupt JPEG that cannot be opened.
            buffer.rewind();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            // Validate: a valid JPEG always starts with FF D8 FF
            if (bytes.length < 3 || (bytes[0] & 0xFF) != 0xFF
                    || (bytes[1] & 0xFF) != 0xD8
                    || (bytes[2] & 0xFF) != 0xFF) {
                Log.e(TAG, "Invalid JPEG header — skipping frame (size=" + bytes.length + ")");
                // schedulePhoto called in finally block
                return;
            }

            // Write to disk — flush() + close() ensure all bytes reach the FS
            // before we hand the path to the Cloudinary uploader.
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.flush();
            fos.close();

            long fileSizeKb = file.length() / 1024;
            photoCount++;
            Log.d(TAG, "Photo saved: " + file.getName()
                    + " size=" + fileSizeKb + "KB"
                    + " (" + photoCount + "/" + MAX_PHOTOS + ")");
            updateNotification("📸 Photo " + photoCount + "/" + MAX_PHOTOS
                    + " captured (" + fileSizeKb + " KB)");

            uploadPhotoToCloudinary(file, ts);

        } catch (Exception e) {
            Log.e(TAG, "onImageAvailable error: " + e.getMessage());
            // schedulePhoto handled in finally block below
        } finally {
            // MUST close the Image to release the buffer back to ImageReader.
            // If not closed, subsequent acquireNextImage() calls return null
            // and no more photos are captured for the rest of the SOS session.
            image.close();
            schedulePhoto(PHOTO_INTERVAL_MS);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Upload photo to Cloudinary → save URL to Firestore
    // ─────────────────────────────────────────────────────────────────────────
    private void uploadPhotoToCloudinary(File file, String timestamp) {
        if (userId == null || !file.exists()) return;

        // Safety check: MediaManager must be initialised in CareLinkApplication.
        // If not initialised (e.g. credentials not set yet), log and skip upload.
        try {
            MediaManager.get();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Cloudinary not initialised. Add credentials to CareLinkApplication.java");
            return;
        }

        MediaManager.get()
                .upload(Uri.fromFile(file))
                .option("folder",        "carelink_evidence/" + userId + "/photos")
                .option("public_id",     "photo_" + timestamp)
                .option("resource_type", "image")
                // No eager transformation: upload returns secure_url immediately.
                // eager_async:false was causing upload TIMEOUT — Cloudinary blocked
                // the response for 10-30s waiting for transformation to finish.
                // The SDK timed out → onError fired → savePhotoUrlToFirestore never called.
                // Using secure_url directly works — Glide loads JPEG from Cloudinary fine.
                .option("overwrite",     true)
                .callback(new UploadCallback() {
                    @Override public void onStart(String id) {}
                    @Override public void onProgress(String id, long b, long t) {}

                    @Override
                    public void onSuccess(String id, Map resultData) {
                        // secure_url is the direct Cloudinary JPEG URL — always available
                        // immediately after upload completes. No eager parsing needed.
                        String url = "";
                        try {
                            Object su = resultData.get("secure_url");
                            if (su != null) url = su.toString();
                        } catch (Exception ex) {
                            Log.e(TAG, "secure_url parse error: " + ex.getMessage());
                        }
                        if (url.isEmpty()) {
                            Log.e(TAG, "Cloudinary returned no URL — skipping");
                            return;
                        }
                        Log.d(TAG, "Photo uploaded OK: " + url);
                        savePhotoUrlToFirestore(url, timestamp);
                        try { file.delete(); } catch (Exception ignored) {}
                    }

                    @Override
                    public void onError(String id, ErrorInfo error) {
                        Log.e(TAG, "Photo upload failed: " + error.getDescription());
                    }

                    @Override public void onReschedule(String id, ErrorInfo error) {}
                })
                .dispatch();
    }

    private void flushPendingPhotoUrls() {
        synchronized (pendingPhotoUrls) {
            for (String[] entry : pendingPhotoUrls) {
                writePhotoUrlToFirestore(entry[0], entry[1]);
            }
            pendingPhotoUrls.clear();
        }
    }

    private void savePhotoUrlToFirestore(String url, String timestamp) {
        // If Firestore doc isn't ready yet, queue the URL — don't use postDelayed
        // because mainHandler is cleared in stopCapture() which kills retries.
        if (evidenceDocId == null) {
            synchronized (pendingPhotoUrls) {
                pendingPhotoUrls.add(new String[]{url, timestamp});
            }
            Log.d(TAG, "Queued photo URL (doc not ready yet): " + url);
            return;
        }
        writePhotoUrlToFirestore(url, timestamp);
    }

    private void writePhotoUrlToFirestore(String url, String timestamp) {
        if (evidenceDocId == null) return;

        String displayTime;
        try {
            // Handle both timestamp formats: yyyyMMdd_HHmmss and yyyyMMdd_HHmmss_SSS
            String tsParseable = timestamp.length() > 15
                    ? timestamp.substring(0, 15) : timestamp;
            Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .parse(tsParseable);
            displayTime = new SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
                    .format(d != null ? d : new Date());
        } catch (Exception e) { displayTime = timestamp; }

        Map<String, Object> photo = new HashMap<>();
        photo.put("url",       url);
        photo.put("timestamp", displayTime);

        FirebaseFirestore.getInstance()
                .collection("evidence").document(evidenceDocId)
                .update("photoUrls", FieldValue.arrayUnion(photo))
                .addOnSuccessListener(v -> Log.d(TAG, "Photo URL saved to Firestore"))
                .addOnFailureListener(e -> Log.e(TAG, "Firestore update failed: " + e.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Audio recording
    // ─────────────────────────────────────────────────────────────────────────
    private void startAudioRecording() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            audioFilePath = new File(getEvidenceDir(), "audio_" + ts + ".m4a").getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            // VOICE_RECOGNITION filters out audio playing through the device speaker
            // (alarm sounds, TTS). MIC captures everything including the SOS alarm.
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            // AAC-LC in MPEG4 container — far better quality than AMR_NB/3GP
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);  // CD quality
            mediaRecorder.setAudioEncodingBitRate(128000); // 128 kbps
            mediaRecorder.setAudioChannels(1);           // mono is fine for voice
            mediaRecorder.setOutputFile(audioFilePath);
            mediaRecorder.setMaxDuration(AUDIO_DURATION_MS);
            mediaRecorder.prepare();
            mediaRecorder.start();
            Log.d(TAG, "Audio recording started");
        } catch (Exception e) {
            Log.e(TAG, "Audio recording failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private void uploadAudioToCloudinary(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || userId == null) return;
        try { MediaManager.get(); } catch (IllegalStateException e) { return; }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        MediaManager.get()
                .upload(Uri.fromFile(file))
                .option("folder",        "carelink_evidence/" + userId + "/audio")
                .option("public_id",     "audio_" + ts)
                .option("resource_type", "video")
                .option("overwrite",     true)
                .callback(new UploadCallback() {
                    @Override public void onStart(String id) {}
                    @Override public void onProgress(String id, long b, long t) {}
                    @Override
                    public void onSuccess(String id, Map resultData) {
                        String url = resultData.get("secure_url").toString();
                        Log.d(TAG, "Audio uploaded: " + url);
                        if (evidenceDocId != null) {
                            FirebaseFirestore.getInstance()
                                    .collection("evidence").document(evidenceDocId)
                                    .update("audioUrl", url, "status", "complete")
                                    .addOnSuccessListener(v ->
                                            Log.d(TAG, "Audio URL saved"));
                        }
                        try { file.delete(); } catch (Exception ignored) {}
                    }
                    @Override
                    public void onError(String id, ErrorInfo error) {
                        Log.e(TAG, "Audio upload failed: " + error.getDescription());
                    }
                    @Override public void onReschedule(String id, ErrorInfo error) {}
                })
                .dispatch();
    }

    private File getEvidenceDir() {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "CareLink_Evidence");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void stopCapture() {
        isCapturing = false;
        mainHandler.removeCallbacksAndMessages(null);

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                if (audioFilePath != null) uploadAudioToCloudinary(audioFilePath);
            }
        } catch (Exception ignored) {}

        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
        try { if (cameraDevice  != null) cameraDevice.close();   } catch (Exception ignored) {}
        try { if (imageReader   != null) imageReader.close();     } catch (Exception ignored) {}
        try { if (dummySurface  != null) dummySurface.release();  } catch (Exception ignored) {}
        try { if (dummyTexture  != null) dummyTexture.release();  } catch (Exception ignored) {}

        if (cameraThread != null) cameraThread.quitSafely();
        if (uploadExecutor != null) uploadExecutor.shutdown();

        updateNotification("✅ Evidence captured and uploading...");
        Log.d(TAG, "Evidence capture stopped");
        stopSelf();
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("CareLink — SOS Evidence")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr != null) mgr.notify(NOTIF_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(new NotificationChannel(
                    CH_ID, "Evidence Capture", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }
}