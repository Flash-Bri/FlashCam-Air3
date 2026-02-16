package com.flashcam.air3;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * FlashCam-Air3 v1.5
 *
 * Full-featured camera for INMO Air3 glasses.
 * - 8MP / 12MP / 16MP still capture (max-res via SENSOR_PIXEL_MODE)
 * - PORTRAIT = center-crop to 3:4 from the landscape capture (no world rotation)
 * - LANDSCAPE = full frame, upright
 * - Deterministic pixel rotation with auto-correct
 * - DNG toggle (proper DngCreator output)
 * - Letterboxed preview with capture-frame overlay
 * - Tap-to-focus mapped within crop region
 * - EV compensation
 * - Video: 1080p30 / 4K30
 * - Gallery integration via MediaStore (scoped storage safe)
 * - Orange + Black branding
 *
 * Developed with assistance from Manus AI and ChatGPT (OpenAI)
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FlashCam";
    private static final int PERM_REQUEST = 100;
    private static final long OPEN_TIMEOUT_MS = 60_000;
    private static final int JPEG_QUALITY = 100;

    // ── Branding colors ──
    private static final int COLOR_ORANGE = 0xFFFF6600;
    private static final int COLOR_ORANGE_DIM = 0xFF993D00;
    private static final int COLOR_RED = 0xFFFF0000;

    // ── UI ──
    private TextureView textureView;
    private CaptureFrameOverlayView captureFrameOverlay;
    private View focusRing;
    private TextView tvStatus, tvMode, tvReceipt, tvEv, tvFocusIndicator;
    private TextView tvRecTimer;
    private Button btnMode, btnDng, btnOrientation, btnVideo, btnDebug, btnCredits;
    private Button btnEvMinus, btnEvPlus;
    private ImageButton btnShutter;
    private LinearLayout receiptPanel;
    private Button btnCopyReceipt, btnExportLog, btnDismiss;
    private Chronometer chronoRec;
    private FrameLayout shutterContainer;
    private View shutterFlashOverlay;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Threading ──
    private HandlerThread camThread, workerThread;
    private Handler camHandler, workerHandler;

    // ── Camera state ──
    private CameraManager cameraManager;
    private String cameraId;
    private CameraCharacteristics camChars;
    private volatile CameraDevice cameraDevice;
    private volatile CameraCaptureSession previewSession;
    private volatile CaptureRequest.Builder previewRequestBuilder;
    private volatile boolean cameraAvailable = false;
    private volatile boolean capturing = false;

    // ── Sizes ──
    private Size defaultJpegSize;
    private Size maxResJpegSize;
    private Size maxResRawSize;
    private Size previewSize;
    private boolean hasRaw = false;

    // ── All available JPEG sizes ──
    private Size[] defaultJpegSizes;
    private Size[] maxResJpegSizes;

    // ── Orientation ──
    private int sensorOrientation = 0;

    // ── Sensor info ──
    private Rect sensorArraySize;
    private Float minFocusDist;
    private boolean canAF = false;

    // ── EV ──
    private Range<Integer> evRange;
    private int currentEv = 0;

    // ── Modes ──
    private enum MpMode { MP8, MP12, MP16 }
    private enum OrientMode { LANDSCAPE, PORTRAIT }
    private enum VideoMode { OFF, V1080P, V4K }
    private MpMode currentMp = MpMode.MP16;
    private OrientMode currentOrient = OrientMode.LANDSCAPE;
    private VideoMode currentVideo = VideoMode.OFF;
    private boolean dngEnabled = false;
    private boolean debugEnabled = false;

    // ── Video recording ──
    private volatile boolean isRecording = false;
    private MediaRecorder mediaRecorder;
    private String currentVideoPath;

    // ── Receipt log ──
    private final List<String> receiptLog = new ArrayList<>();
    private String lastReceipt = "";

    // ── Video sizes ──
    private boolean has4K = false;

    // ── Status state machine ──
    private enum CamState { INITIALIZING, OPENING, READY, CAPTURING, RECORDING, ERROR }
    private volatile CamState camState = CamState.INITIALIZING;
    private volatile long lastStatusChangeMs = 0;
    private static final long STATUS_THROTTLE_MS = 300;

    // ================================================================
    // LIFECYCLE
    // ================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bindViews();
        setupListeners();

        camThread = new HandlerThread("CamThread");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        workerThread = new HandlerThread("WorkerThread");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        textureView.setSurfaceTextureListener(surfaceListener);

        requestPermissions();
    }

    private void bindViews() {
        textureView = findViewById(R.id.textureView);
        captureFrameOverlay = findViewById(R.id.captureFrameOverlay);
        focusRing = findViewById(R.id.focusRing);
        tvStatus = findViewById(R.id.tvStatus);
        tvMode = findViewById(R.id.tvMode);
        tvReceipt = findViewById(R.id.tvReceipt);
        tvEv = findViewById(R.id.tvEv);
        tvFocusIndicator = findViewById(R.id.tvFocusIndicator);
        tvRecTimer = findViewById(R.id.tvRecTimer);
        btnMode = findViewById(R.id.btnMode);
        btnDng = findViewById(R.id.btnDng);
        btnOrientation = findViewById(R.id.btnOrientation);
        btnVideo = findViewById(R.id.btnVideo);
        btnDebug = findViewById(R.id.btnDebug);
        btnCredits = findViewById(R.id.btnCredits);
        btnEvMinus = findViewById(R.id.btnEvMinus);
        btnEvPlus = findViewById(R.id.btnEvPlus);
        btnShutter = findViewById(R.id.btnShutter);
        receiptPanel = findViewById(R.id.receiptPanel);
        btnCopyReceipt = findViewById(R.id.btnCopyReceipt);
        btnExportLog = findViewById(R.id.btnExportLog);
        btnDismiss = findViewById(R.id.btnDismiss);
        chronoRec = findViewById(R.id.chronoRec);
        shutterContainer = findViewById(R.id.shutterContainer);
        shutterFlashOverlay = findViewById(R.id.shutterFlashOverlay);
    }

    private void setupListeners() {
        btnShutter.setEnabled(false);
        btnShutter.setOnClickListener(v -> onShutterPress());
        btnMode.setOnClickListener(v -> cycleMpMode());
        btnDng.setOnClickListener(v -> toggleDng());
        btnOrientation.setOnClickListener(v -> toggleOrientation());
        btnVideo.setOnClickListener(v -> cycleVideoMode());
        btnDebug.setOnClickListener(v -> toggleDebug());
        btnCredits.setOnClickListener(v -> showCredits());
        btnEvMinus.setOnClickListener(v -> adjustEv(-1));
        btnEvPlus.setOnClickListener(v -> adjustEv(+1));
        btnCopyReceipt.setOnClickListener(v -> copyReceipt());
        btnExportLog.setOnClickListener(v -> exportLog());
        btnDismiss.setOnClickListener(v -> receiptPanel.setVisibility(View.GONE));

        textureView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && !isRecording) {
                float tx = event.getX(), ty = event.getY();
                if (captureFrameOverlay != null && !captureFrameOverlay.isInsideClearRect(tx, ty)) {
                    return false;
                }
                handleTapToFocus(tx, ty);
                return true;
            }
            return false;
        });

        updateAllUI();
    }

    private void showCredits() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("FlashCam-Air3 Credits")
            .setMessage("FlashCam-Air3 v1.5\n\n"
                + "Developed with assistance from Manus AI and ChatGPT (OpenAI)\n\n"
                + "Unlocks the full 16MP sensor on INMO Air3 glasses using the "
                + "standard Android Camera2 SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION API.\n\n"
                + "No root required. No vendor file edits. No firmware mods.\n\n"
                + "https://manus.im\nhttps://openai.com\n"
                + "https://github.com/Flash-Bri/FlashCam-Air3")
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (camThread == null || !camThread.isAlive()) {
            camThread = new HandlerThread("CamThread");
            camThread.start();
            camHandler = new Handler(camThread.getLooper());
        }
        if (workerThread == null || !workerThread.isAlive()) {
            workerThread = new HandlerThread("WorkerThread");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
        }
        if (textureView.isAvailable() && cameraDevice == null && cameraId != null) {
            workerHandler.post(this::openCamera);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        if (camThread != null) { camThread.quitSafely(); camThread = null; }
        if (workerThread != null) { workerThread.quitSafely(); workerThread = null; }
    }

    private void closeCamera() {
        try {
            if (isRecording) {
                try { mediaRecorder.stop(); } catch (Exception ignored) {}
                try { mediaRecorder.release(); } catch (Exception ignored) {}
                mediaRecorder = null;
                isRecording = false;
            }
            if (previewSession != null) { previewSession.close(); previewSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        } catch (Exception ignored) {}
    }

    // ================================================================
    // PERMISSIONS
    // ================================================================
    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST);
        } else {
            initCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST) {
            boolean cameraGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.CAMERA.equals(permissions[i])
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    cameraGranted = true;
                }
            }
            if (cameraGranted) initCamera();
            else setStatusForced("Camera permission denied");
        }
    }

    // ================================================================
    // CAMERA INIT
    // ================================================================
    private void initCamera() {
        try {
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    camChars = cc;
                    break;
                }
            }
            if (cameraId == null && ids.length > 0) {
                cameraId = ids[0];
                camChars = cameraManager.getCameraCharacteristics(cameraId);
            }
            if (cameraId == null) { setStatusForced("No camera found"); return; }

            Integer so = camChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = (so != null) ? so : 0;

            sensorArraySize = camChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            minFocusDist = camChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            canAF = (minFocusDist != null && minFocusDist > 0);

            evRange = camChars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);

            // Default stream map
            StreamConfigurationMap defaultMap =
                camChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (defaultMap != null) {
                defaultJpegSizes = defaultMap.getOutputSizes(ImageFormat.JPEG);
                if (defaultJpegSizes != null && defaultJpegSizes.length > 0) {
                    defaultJpegSize = findLargest(defaultJpegSizes);
                }
                Size[] previewSizes = defaultMap.getOutputSizes(SurfaceTexture.class);
                if (previewSizes != null && previewSizes.length > 0) {
                    previewSize = findBest43Preview(previewSizes);
                }
                // Check for 4K video
                Size[] videoSizes = defaultMap.getOutputSizes(MediaRecorder.class);
                if (videoSizes != null) {
                    for (Size s : videoSizes) {
                        if (s.getWidth() >= 3840 && s.getHeight() >= 2160) { has4K = true; break; }
                    }
                }
            }

            // Max-res stream map (API 31+)
            try {
                Object maxResMap = camChars.getClass()
                    .getMethod("get", CameraCharacteristics.Key.class)
                    .invoke(camChars, new CameraCharacteristics.Key<StreamConfigurationMap>(
                        "android.scaler.streamConfigurationMapMaximumResolution",
                        StreamConfigurationMap.class));
                if (maxResMap instanceof StreamConfigurationMap) {
                    StreamConfigurationMap mrMap = (StreamConfigurationMap) maxResMap;
                    maxResJpegSizes = mrMap.getOutputSizes(ImageFormat.JPEG);
                    if (maxResJpegSizes != null && maxResJpegSizes.length > 0) {
                        maxResJpegSize = findLargest(maxResJpegSizes);
                    }
                    Size[] rawSizes = mrMap.getOutputSizes(ImageFormat.RAW_SENSOR);
                    if (rawSizes != null && rawSizes.length > 0) {
                        maxResRawSize = findLargest(rawSizes);
                        hasRaw = true;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Max-res stream map not available: " + e.getMessage());
            }

            // Register availability callback
            cameraManager.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
                @Override public void onCameraAvailable(@NonNull String id) {
                    if (id.equals(cameraId)) cameraAvailable = true;
                }
                @Override public void onCameraUnavailable(@NonNull String id) {
                    if (id.equals(cameraId)) cameraAvailable = false;
                }
            }, mainHandler);

            if (textureView.isAvailable()) {
                workerHandler.post(this::openCamera);
            }

        } catch (Exception e) {
            setStatusForced("Init error: " + e.getMessage());
        }
    }

    // ================================================================
    // CAMERA OPEN (with retry)
    // ================================================================
    private void openCamera() {
        if (cameraId == null || cameraDevice != null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;

        for (int attempt = 1; attempt <= 3; attempt++) {
            transitionState(CamState.OPENING);
            final int att = attempt;
            setStatusForced("Opening camera (attempt " + att + "/3)...");

            try {
                final Object lock = new Object();
                final int[] result = {-999};

                Executor exec = camHandler::post;
                cameraManager.openCamera(cameraId, exec, new CameraDevice.StateCallback() {
                    @Override public void onOpened(@NonNull CameraDevice camera) {
                        cameraDevice = camera;
                        synchronized (lock) { result[0] = 0; lock.notifyAll(); }
                    }
                    @Override public void onDisconnected(@NonNull CameraDevice camera) {
                        camera.close(); cameraDevice = null;
                        synchronized (lock) { result[0] = -2; lock.notifyAll(); }
                    }
                    @Override public void onError(@NonNull CameraDevice camera, int error) {
                        camera.close(); cameraDevice = null;
                        synchronized (lock) { result[0] = error; lock.notifyAll(); }
                    }
                });

                synchronized (lock) {
                    if (result[0] == -999) lock.wait(OPEN_TIMEOUT_MS);
                }

                if (result[0] == 0 && cameraDevice != null) {
                    startPreview();
                    return;
                }

                setStatusForced("Open failed (code " + result[0] + "), retry in 2s...");
                Thread.sleep(2000);

            } catch (Exception e) {
                setStatusForced("Open error: " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
        transitionState(CamState.ERROR);
    }

    // ================================================================
    // STATUS STATE MACHINE
    // ================================================================
    private void transitionState(CamState newState) {
        if (newState == camState) return;
        long now = System.currentTimeMillis();
        if (now - lastStatusChangeMs < STATUS_THROTTLE_MS && newState != CamState.ERROR
                && newState != CamState.CAPTURING && newState != CamState.RECORDING) {
            return;
        }
        camState = newState;
        lastStatusChangeMs = now;
        String msg;
        switch (newState) {
            case INITIALIZING: msg = "Initializing..."; break;
            case OPENING:      msg = "Opening camera..."; break;
            case READY:        msg = "Ready"; break;
            case CAPTURING:    msg = "Hold still..."; break;
            case RECORDING:    msg = "REC"; break;
            case ERROR:        msg = "Camera error"; break;
            default:           msg = ""; break;
        }
        mainHandler.post(() -> tvStatus.setText(msg));
    }

    private void setStatusForced(String msg) {
        lastStatusChangeMs = 0;
        mainHandler.post(() -> tvStatus.setText(msg));
    }

    // ================================================================
    // PREVIEW
    // ================================================================
    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) return;

        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;

            Size ps = previewSize != null ? previewSize : new Size(1440, 1080);
            st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
            Surface surface = new Surface(st);

            mainHandler.post(this::configurePreviewTransform);
            mainHandler.post(this::updateCaptureFrameOverlay);

            OutputConfiguration outConfig = new OutputConfiguration(surface);

            previewRequestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                canAF ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                      : CaptureRequest.CONTROL_AF_MODE_OFF);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv);

            Executor exec = camHandler::post;
            SessionConfiguration sessConfig = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, Arrays.asList(outConfig), exec,
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        previewSession = session;
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(),
                                previewCaptureCallback, camHandler);
                            transitionState(CamState.READY);
                            mainHandler.post(() -> btnShutter.setEnabled(true));
                        } catch (Exception e) {
                            setStatusForced("Preview error: " + e.getMessage());
                        }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        transitionState(CamState.ERROR);
                    }
                });

            cameraDevice.createCaptureSession(sessConfig);
        } catch (Exception e) {
            setStatusForced("Preview error: " + e.getMessage());
        }
    }

    private final CameraCaptureSession.CaptureCallback previewCaptureCallback =
        new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                    @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                updateFocusAeIndicator(afState, aeState);
            }
        };

    private void updateFocusAeIndicator(Integer afState, Integer aeState) {
        String afStr = "AF:--";
        if (afState != null) {
            switch (afState) {
                case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:     afStr = "AF:LOCK"; break;
                case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: afStr = "AF:FAIL"; break;
                case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:    afStr = "AF:OK"; break;
                case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:       afStr = "AF:..."; break;
                case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN:        afStr = "AF:SCAN"; break;
                default: afStr = "AF:" + afState; break;
            }
        }
        String aeStr = "AE:--";
        if (aeState != null) {
            switch (aeState) {
                case CaptureResult.CONTROL_AE_STATE_CONVERGED:      aeStr = "AE:OK"; break;
                case CaptureResult.CONTROL_AE_STATE_LOCKED:         aeStr = "AE:LOCK"; break;
                case CaptureResult.CONTROL_AE_STATE_SEARCHING:      aeStr = "AE:..."; break;
                case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: aeStr = "AE:FLASH"; break;
                default: aeStr = "AE:" + aeState; break;
            }
        }
        final String txt = afStr + " | " + aeStr;
        mainHandler.post(() -> { if (tvFocusIndicator != null) tvFocusIndicator.setText(txt); });
    }

    // ================================================================
    // PREVIEW TRANSFORM — LETTERBOX (FIT, not CROP)
    // ================================================================
    private void configurePreviewTransform() {
        if (previewSize == null || textureView == null) return;
        int viewW = textureView.getWidth(), viewH = textureView.getHeight();
        if (viewW == 0 || viewH == 0) return;

        int previewW = previewSize.getWidth(), previewH = previewSize.getHeight();
        Matrix matrix = new Matrix();
        float centerX = viewW / 2f, centerY = viewH / 2f;

        float scaleX = (float) viewW / previewW;
        float scaleY = (float) viewH / previewH;
        float scale = Math.min(scaleX, scaleY);
        matrix.setScale(scale, scale, centerX, centerY);
        textureView.setTransform(matrix);
    }

    // ================================================================
    // CAPTURE FRAME OVERLAY
    // ================================================================
    private void updateCaptureFrameOverlay() {
        if (captureFrameOverlay == null || textureView == null || previewSize == null) return;

        int viewW = textureView.getWidth(), viewH = textureView.getHeight();
        if (viewW == 0 || viewH == 0) return;

        int previewW = previewSize.getWidth(), previewH = previewSize.getHeight();
        float scaleX = (float) viewW / previewW;
        float scaleY = (float) viewH / previewH;
        float scale = Math.min(scaleX, scaleY);

        float renderedW = previewW * scale;
        float renderedH = previewH * scale;
        float offsetX = (viewW - renderedW) / 2f;
        float offsetY = (viewH - renderedH) / 2f;

        RectF clearRect;
        if (currentOrient == OrientMode.PORTRAIT) {
            // Portrait: 3:4 aspect (width < height) center-cropped from the 4:3 preview
            // The preview is landscape (W > H). A 3:4 portrait crop means:
            // cropAspect = 3/4 = 0.75 (width/height)
            float captureAspect = 3f / 4f;
            float previewAspect = renderedW / renderedH;
            float cropW, cropH;
            if (captureAspect < previewAspect) {
                cropH = renderedH;
                cropW = cropH * captureAspect;
            } else {
                cropW = renderedW;
                cropH = cropW / captureAspect;
            }
            float cx = viewW / 2f, cy = viewH / 2f;
            clearRect = new RectF(cx - cropW / 2f, cy - cropH / 2f,
                                   cx + cropW / 2f, cy + cropH / 2f);
        } else {
            clearRect = new RectF(offsetX, offsetY,
                                   offsetX + renderedW, offsetY + renderedH);
        }

        captureFrameOverlay.setClearRect(clearRect);
    }

    // ================================================================
    // TAP-TO-FOCUS (mapped within crop region for portrait)
    // ================================================================
    private void handleTapToFocus(float touchX, float touchY) {
        if (previewSession == null || previewRequestBuilder == null || cameraDevice == null) return;
        if (sensorArraySize == null) return;

        int viewW = textureView.getWidth(), viewH = textureView.getHeight();
        if (viewW == 0 || viewH == 0) return;

        // Get the letterbox offset
        int previewW = previewSize.getWidth(), previewH = previewSize.getHeight();
        float scaleX = (float) viewW / previewW;
        float scaleY = (float) viewH / previewH;
        float scale = Math.min(scaleX, scaleY);
        float renderedW = previewW * scale;
        float renderedH = previewH * scale;
        float offsetX = (viewW - renderedW) / 2f;
        float offsetY = (viewH - renderedH) / 2f;

        // Map touch to normalized preview coordinates (0..1)
        float normX = (touchX - offsetX) / renderedW;
        float normY = (touchY - offsetY) / renderedH;
        normX = Math.max(0f, Math.min(1f, normX));
        normY = Math.max(0f, Math.min(1f, normY));

        // For portrait mode, the tap is already within the clearRect (checked by listener).
        // The normX/normY above maps to the full sensor, which is correct because
        // the crop happens post-capture. The sensor still sees the full frame during preview.

        int sW = sensorArraySize.width(), sH = sensorArraySize.height();
        int regionW = (int)(sW * 0.1f), regionH = (int)(sH * 0.1f);
        int cx = (int)(normX * sW), cy = (int)(normY * sH);
        int left = Math.max(0, cx - regionW / 2);
        int top = Math.max(0, cy - regionH / 2);
        int right = Math.min(sW, left + regionW);
        int bottom = Math.min(sH, top + regionH);

        MeteringRectangle[] region = new MeteringRectangle[]{
            new MeteringRectangle(left, top, right - left, bottom - top, 1000)
        };

        showFocusRing(touchX, touchY);

        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, region);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, region);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START);
            previewSession.capture(previewRequestBuilder.build(), previewCaptureCallback, camHandler);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            previewSession.setRepeatingRequest(previewRequestBuilder.build(),
                previewCaptureCallback, camHandler);
        } catch (Exception e) { setStatusForced("Focus error: " + e.getMessage()); }
    }

    private void showFocusRing(float x, float y) {
        if (focusRing == null) return;
        mainHandler.post(() -> {
            focusRing.setX(x - focusRing.getWidth() / 2f);
            focusRing.setY(y - focusRing.getHeight() / 2f);
            focusRing.setVisibility(View.VISIBLE);
            focusRing.setAlpha(1f);
            focusRing.animate().alpha(0f).setDuration(1500)
                .withEndAction(() -> focusRing.setVisibility(View.GONE)).start();
        });
    }

    // ================================================================
    // EV
    // ================================================================
    private void adjustEv(int delta) {
        if (evRange == null) return;
        int newEv = currentEv + delta;
        if (newEv < evRange.getLower() || newEv > evRange.getUpper()) return;
        currentEv = newEv;
        updateEvUI();
        if (previewRequestBuilder != null && previewSession != null) {
            try {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv);
                previewSession.setRepeatingRequest(previewRequestBuilder.build(),
                    previewCaptureCallback, camHandler);
            } catch (Exception ignored) {}
        }
    }

    private void updateEvUI() {
        mainHandler.post(() -> tvEv.setText((currentEv >= 0 ? "+" : "") + currentEv + " EV"));
    }

    // ================================================================
    // MODE TOGGLES
    // ================================================================
    private void cycleMpMode() {
        if (isRecording) return;
        switch (currentMp) {
            case MP8:  currentMp = MpMode.MP12; break;
            case MP12: currentMp = MpMode.MP16; break;
            case MP16: currentMp = MpMode.MP8;  break;
        }
        updateAllUI();
    }

    private void toggleDng() {
        if (isRecording) return;
        dngEnabled = !dngEnabled;
        updateAllUI();
    }

    private void toggleOrientation() {
        if (isRecording) return;
        currentOrient = (currentOrient == OrientMode.LANDSCAPE) ?
            OrientMode.PORTRAIT : OrientMode.LANDSCAPE;
        updateAllUI();
        mainHandler.post(this::updateCaptureFrameOverlay);
    }

    private void cycleVideoMode() {
        if (isRecording) return;
        switch (currentVideo) {
            case OFF:    currentVideo = VideoMode.V1080P; break;
            case V1080P: currentVideo = has4K ? VideoMode.V4K : VideoMode.OFF; break;
            case V4K:    currentVideo = VideoMode.OFF; break;
        }
        updateAllUI();
    }

    private void toggleDebug() {
        debugEnabled = !debugEnabled;
        updateAllUI();
    }

    private void updateAllUI() {
        mainHandler.post(() -> {
            String mpLabel;
            switch (currentMp) {
                case MP8:  mpLabel = "8 MP"; break;
                case MP12: mpLabel = "12 MP"; break;
                default:   mpLabel = "16 MP"; break;
            }
            btnMode.setText(mpLabel);
            tvMode.setText(mpLabel + (currentOrient == OrientMode.PORTRAIT ? " PORT" : " LAND"));

            btnDng.setText(dngEnabled ? "DNG:ON" : "DNG:OFF");
            btnDng.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                dngEnabled ? COLOR_ORANGE : 0xFF333333));

            btnOrientation.setText(currentOrient == OrientMode.LANDSCAPE ? "LAND" : "PORT");

            String vidLabel;
            switch (currentVideo) {
                case V1080P: vidLabel = "1080p"; break;
                case V4K:    vidLabel = "4K"; break;
                default:     vidLabel = "PHOTO"; break;
            }
            btnVideo.setText(vidLabel);

            if (currentVideo != VideoMode.OFF) {
                btnShutter.setBackground(getDrawable(R.drawable.record_button));
            } else {
                btnShutter.setBackground(getDrawable(R.drawable.shutter_button));
            }

            btnDebug.setText(debugEnabled ? "DBG:ON" : "DBG:OFF");
            btnDebug.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                debugEnabled ? COLOR_ORANGE_DIM : 0xFF222222));

            updateEvUI();

            if (!isRecording) {
                if (chronoRec != null) chronoRec.setVisibility(View.GONE);
            }
        });
    }

    // ================================================================
    // SURFACE LISTENER
    // ================================================================
    private final TextureView.SurfaceTextureListener surfaceListener =
        new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                configurePreviewTransform();
                updateCaptureFrameOverlay();
                if (cameraId != null && cameraDevice == null)
                    workerHandler.post(() -> openCamera());
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                configurePreviewTransform();
                updateCaptureFrameOverlay();
            }
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        };

    // ================================================================
    // SHUTTER PRESS
    // ================================================================
    private void onShutterPress() {
        if (currentVideo != VideoMode.OFF) {
            if (isRecording) { stopRecording(); } else { startRecording(); }
            return;
        }

        if (capturing) return;
        capturing = true;
        btnShutter.setEnabled(false);

        flashShutter();
        transitionState(CamState.CAPTURING);

        workerHandler.post(() -> {
            try {
                if (previewSession != null) {
                    previewSession.close();
                    previewSession = null;
                    Thread.sleep(200);
                }
            } catch (Exception ignored) {}

            if (cameraDevice == null) {
                openCamera();
                if (cameraDevice == null) {
                    finishCapture("Camera unavailable");
                    return;
                }
            }

            Size targetSize = getTargetJpegSize();
            boolean useMaxRes = needsMaxRes();
            boolean wantDng = dngEnabled && hasRaw && useMaxRes;

            doCapture(targetSize, useMaxRes, wantDng);
        });
    }

    private void flashShutter() {
        mainHandler.post(() -> {
            if (shutterFlashOverlay != null) {
                shutterFlashOverlay.setBackgroundColor(Color.WHITE);
                shutterFlashOverlay.setAlpha(0.7f);
                shutterFlashOverlay.setVisibility(View.VISIBLE);
                shutterFlashOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> {
                        shutterFlashOverlay.setVisibility(View.GONE);
                        shutterFlashOverlay.setBackgroundColor(Color.TRANSPARENT);
                    })
                    .start();
            }
        });
    }

    // ================================================================
    // SIZE SELECTION
    // ================================================================
    private Size getTargetJpegSize() {
        long targetPixels;
        switch (currentMp) {
            case MP8:  targetPixels = 8_000_000L; break;
            case MP12: targetPixels = 12_000_000L; break;
            default:   targetPixels = 16_000_000L; break;
        }

        if (currentMp == MpMode.MP16 && maxResJpegSize != null) {
            return maxResJpegSize;
        }

        Size best = findClosestSize(defaultJpegSizes, targetPixels);
        if (best != null) {
            long bestPx = (long) best.getWidth() * best.getHeight();
            if (bestPx < targetPixels * 0.5 && maxResJpegSizes != null) {
                Size mrBest = findClosestSize(maxResJpegSizes, targetPixels);
                if (mrBest != null) return mrBest;
            }
            return best;
        }

        if (maxResJpegSizes != null) {
            Size mrBest = findClosestSize(maxResJpegSizes, targetPixels);
            if (mrBest != null) return mrBest;
        }

        return defaultJpegSize != null ? defaultJpegSize : new Size(2048, 1536);
    }

    private boolean needsMaxRes() {
        Size target = getTargetJpegSize();
        if (target == null) return false;
        if (defaultJpegSizes != null) {
            for (Size s : defaultJpegSizes) {
                if (s.equals(target)) return false;
            }
        }
        return true;
    }

    private Size findClosestSize(Size[] sizes, long targetPixels) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            long px = (long) s.getWidth() * s.getHeight();
            long diff = Math.abs(px - targetPixels);
            if (diff < bestDiff) { best = s; bestDiff = diff; }
        }
        return best;
    }

    // ================================================================
    // ORIENTATION — deterministic, with auto-correct
    // ================================================================

    /**
     * Returns the CW rotation needed to make the sensor output upright
     * for the current device display orientation.
     *
     * For INMO Air3 (sensorOrientation=270, always landscape display rotation=0):
     *   baseRotation = (270 - 0 + 360) % 360 = 270
     *
     * This is the rotation for LANDSCAPE output (full frame, upright, W > H).
     */
    private int getBaseRotation() {
        int deviceRotation = 0;
        try {
            int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
            int[] degreesMap = {0, 90, 180, 270};
            deviceRotation = degreesMap[displayRotation];
        } catch (Exception ignored) {}
        return (sensorOrientation - deviceRotation + 360) % 360;
    }

    /**
     * Rotate a JPEG byte array by the given degrees (pixel rotation).
     * Returns the rotated JPEG bytes, or original on failure.
     */
    private byte[] rotateJpegPixels(byte[] jpegData, int degrees) {
        if (degrees == 0) return jpegData;

        try {
            Bitmap original = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (original == null) return jpegData;

            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);

            Bitmap rotated = Bitmap.createBitmap(original,
                0, 0, original.getWidth(), original.getHeight(), matrix, true);
            original.recycle();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            rotated.recycle();

            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Pixel rotation failed", e);
            return jpegData;
        }
    }

    /**
     * Center-crop a JPEG to 3:4 portrait aspect ratio.
     * Input is expected to be an upright landscape image (W > H).
     * Output is a portrait image (H > W) with the center portion preserved.
     *
     * Returns: Object[] {croppedJpegBytes, cropX, cropY, cropW, cropH}
     * The crop info is returned for the receipt.
     */
    private Object[] cropToPortrait(byte[] jpegData) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bmp == null) return new Object[]{jpegData, 0, 0, 0, 0};

            int w = bmp.getWidth(), h = bmp.getHeight();
            // Target: 3:4 (portrait). From landscape W>H, crop width.
            float targetAspect = 3f / 4f; // width / height
            int cropW, cropH;
            if ((float) w / h > targetAspect) {
                // Image is wider than 3:4 — crop width
                cropH = h;
                cropW = Math.round(h * targetAspect);
            } else {
                // Image is taller or equal — crop height
                cropW = w;
                cropH = Math.round(w / targetAspect);
            }
            int cropX = (w - cropW) / 2;
            int cropY = (h - cropH) / 2;

            Bitmap cropped = Bitmap.createBitmap(bmp, cropX, cropY, cropW, cropH);
            bmp.recycle();

            // Rotate 90° CW so the portrait image has H > W
            Matrix m = new Matrix();
            m.postRotate(90);
            Bitmap portrait = Bitmap.createBitmap(cropped,
                0, 0, cropped.getWidth(), cropped.getHeight(), m, true);
            cropped.recycle();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            portrait.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            portrait.recycle();

            return new Object[]{bos.toByteArray(), cropX, cropY, cropW, cropH};
        } catch (Exception e) {
            Log.e(TAG, "Portrait crop failed", e);
            return new Object[]{jpegData, 0, 0, 0, 0};
        }
    }

    // ================================================================
    // CAPTURE
    // ================================================================
    private void doCapture(Size jpegSize, boolean maxRes, boolean wantDng) {
        ImageReader jpegReader = null;
        ImageReader rawReader = null;

        try {
            setStatusForced("Hold still...");

            jpegReader = ImageReader.newInstance(
                jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 1);
            final byte[][] jpegData = {null};
            final int[][] dims = new int[2][2]; // [0]=jpeg, [1]=raw
            final Object imgLock = new Object();

            jpegReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img != null) {
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        jpegData[0] = new byte[buf.remaining()];
                        buf.get(jpegData[0]);
                        dims[0][0] = img.getWidth();
                        dims[0][1] = img.getHeight();
                    }
                } finally {
                    if (img != null) img.close();
                    synchronized (imgLock) { imgLock.notifyAll(); }
                }
            }, camHandler);

            final Image[] rawImage = {null};
            if (wantDng && maxResRawSize != null) {
                rawReader = ImageReader.newInstance(
                    maxResRawSize.getWidth(), maxResRawSize.getHeight(),
                    ImageFormat.RAW_SENSOR, 1);
                rawReader.setOnImageAvailableListener(reader -> {
                    rawImage[0] = reader.acquireLatestImage();
                    if (rawImage[0] != null) {
                        dims[1][0] = rawImage[0].getWidth();
                        dims[1][1] = rawImage[0].getHeight();
                    }
                    synchronized (imgLock) { imgLock.notifyAll(); }
                }, camHandler);
            }

            // Build output configurations
            List<OutputConfiguration> outputs = new ArrayList<>();
            OutputConfiguration jpegOutConfig = new OutputConfiguration(jpegReader.getSurface());
            OutputConfiguration rawOutConfig = null;

            if (maxRes) {
                try {
                    jpegOutConfig.getClass().getMethod("setSensorPixelModeUsed", int.class)
                        .invoke(jpegOutConfig, 1);
                } catch (Exception e) {
                    Log.w(TAG, "setSensorPixelModeUsed failed on JPEG output: " + e.getMessage());
                }
            }
            outputs.add(jpegOutConfig);

            if (rawReader != null) {
                rawOutConfig = new OutputConfiguration(rawReader.getSurface());
                if (maxRes) {
                    try {
                        rawOutConfig.getClass().getMethod("setSensorPixelModeUsed", int.class)
                            .invoke(rawOutConfig, 1);
                    } catch (Exception e) {
                        Log.w(TAG, "setSensorPixelModeUsed failed on RAW output: " + e.getMessage());
                    }
                }
                outputs.add(rawOutConfig);
            }

            // Create session
            Executor exec = camHandler::post;
            final Object sessLock = new Object();
            final CameraCaptureSession[] sessHolder = {null};
            final boolean[] sessOk = {false};

            SessionConfiguration sessConfig = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, exec,
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        sessHolder[0] = session;
                        sessOk[0] = true;
                        synchronized (sessLock) { sessLock.notifyAll(); }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        synchronized (sessLock) { sessLock.notifyAll(); }
                    }
                });

            // Set session parameters for max-res
            if (maxRes) {
                try {
                    CaptureRequest.Builder sessParamBuilder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    CaptureRequest.Key<Integer> pixelModeKey =
                        new CaptureRequest.Key<>("android.sensor.pixelMode", Integer.class);
                    sessParamBuilder.set(pixelModeKey, 1);
                    sessConfig.setSessionParameters(sessParamBuilder.build());
                } catch (Exception e) {
                    Log.w(TAG, "Session params pixelMode failed: " + e.getMessage());
                }
            }

            cameraDevice.createCaptureSession(sessConfig);
            synchronized (sessLock) {
                if (!sessOk[0]) sessLock.wait(30_000);
            }
            if (!sessOk[0]) { finishCapture("Session config failed"); return; }

            CameraCaptureSession session = sessHolder[0];

            // Build capture request
            CaptureRequest.Builder capBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            capBuilder.addTarget(jpegReader.getSurface());
            if (rawReader != null) capBuilder.addTarget(rawReader.getSurface());

            capBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            capBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv);
            capBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY);

            if (maxRes) {
                try {
                    CaptureRequest.Key<Integer> pixelModeKey =
                        new CaptureRequest.Key<>("android.sensor.pixelMode", Integer.class);
                    capBuilder.set(pixelModeKey, 1);
                } catch (Exception e) {
                    Log.w(TAG, "CaptureRequest pixelMode failed: " + e.getMessage());
                }
            }

            // Set JPEG_ORIENTATION to make the sensor output upright
            int baseRotation = getBaseRotation();
            capBuilder.set(CaptureRequest.JPEG_ORIENTATION, baseRotation);

            // Fire capture
            final Object capLock = new Object();
            final boolean[] capOk = {false};
            final TotalCaptureResult[] capResultHolder = {null};

            session.capture(capBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                        @NonNull CaptureRequest r, @NonNull TotalCaptureResult result) {
                    capResultHolder[0] = result;
                    capOk[0] = true;
                    synchronized (capLock) { capLock.notifyAll(); }
                }
                @Override public void onCaptureFailed(@NonNull CameraCaptureSession s,
                        @NonNull CaptureRequest r,
                        @NonNull android.hardware.camera2.CaptureFailure failure) {
                    synchronized (capLock) { capLock.notifyAll(); }
                }
            }, camHandler);

            synchronized (capLock) {
                if (!capOk[0]) capLock.wait(30_000);
            }

            setStatusForced("Captured! Processing... (you can move)");

            if (!capOk[0]) { session.close(); finishCapture("Capture failed"); return; }

            synchronized (imgLock) { if (jpegData[0] == null) imgLock.wait(15_000); }
            if (rawReader != null && rawImage[0] == null) {
                synchronized (imgLock) { if (rawImage[0] == null) imgLock.wait(15_000); }
            }

            session.close();

            // ── Process and save ──
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String mpLabel;
            switch (currentMp) {
                case MP8:  mpLabel = "8MP"; break;
                case MP12: mpLabel = "12MP"; break;
                default:   mpLabel = "16MP"; break;
            }

            StringBuilder receipt = new StringBuilder();
            receipt.append("\u2550\u2550\u2550 CAPTURE RECEIPT \u2550\u2550\u2550\n");
            receipt.append("Time: ").append(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
            receipt.append("Mode: ").append(mpLabel).append(maxRes ? " (MAX-RES)" : " (DEFAULT)").append("\n");
            receipt.append("Orientation: ").append(currentOrient).append("\n");
            receipt.append("sensorOrientation: ").append(sensorOrientation).append("\u00B0\n");
            receipt.append("JPEG_ORIENTATION sent: ").append(baseRotation).append("\u00B0\n");
            receipt.append("EV: ").append((currentEv >= 0 ? "+" : "")).append(currentEv).append("\n");

            // Save JPEG
            if (jpegData[0] != null) {
                setStatusForced("Processing JPEG...");

                byte[] finalJpeg;
                boolean isPortrait = (currentOrient == OrientMode.PORTRAIT);
                int cropX = 0, cropY = 0, cropW = 0, cropH = 0;

                if (isPortrait) {
                    // The JPEG from the sensor is already rotated upright (landscape, W > H)
                    // via JPEG_ORIENTATION. Now center-crop to 3:4 and rotate to portrait.
                    Object[] cropResult = cropToPortrait(jpegData[0]);
                    finalJpeg = (byte[]) cropResult[0];
                    cropX = (int) cropResult[1];
                    cropY = (int) cropResult[2];
                    cropW = (int) cropResult[3];
                    cropH = (int) cropResult[4];
                } else {
                    // Landscape: use as-is (JPEG_ORIENTATION already made it upright)
                    finalJpeg = jpegData[0];
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(finalJpeg, 0, finalJpeg.length, opts);
                int dw = opts.outWidth, dh = opts.outHeight;
                double mp = (long) dw * dh / 1e6;

                // Auto-correct: verify orientation
                boolean orientCorrect;
                if (isPortrait) {
                    orientCorrect = (dh >= dw);
                    if (!orientCorrect) {
                        // Emergency fix: rotate 90
                        finalJpeg = rotateJpegPixels(finalJpeg, 90);
                        opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(finalJpeg, 0, finalJpeg.length, opts);
                        dw = opts.outWidth; dh = opts.outHeight;
                        mp = (long) dw * dh / 1e6;
                        orientCorrect = (dh >= dw);
                    }
                } else {
                    orientCorrect = (dw >= dh);
                    if (!orientCorrect) {
                        finalJpeg = rotateJpegPixels(finalJpeg, 90);
                        opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(finalJpeg, 0, finalJpeg.length, opts);
                        dw = opts.outWidth; dh = opts.outHeight;
                        mp = (long) dw * dh / 1e6;
                        orientCorrect = (dw >= dh);
                    }
                }

                String orientSuffix = isPortrait ? "_port" : "_land";
                String jname = "FlashCam_" + ts + "_" + mpLabel + orientSuffix + ".jpg";
                File savedFile = saveToMediaStore(finalJpeg, jname, "image/jpeg");

                receipt.append("\u2500\u2500 JPEG \u2500\u2500\n");
                receipt.append("Requested: ").append(jpegSize.getWidth()).append("x")
                    .append(jpegSize.getHeight()).append("\n");
                if (isPortrait) {
                    receipt.append("Crop: center ").append(cropW).append("x").append(cropH)
                        .append(" from (").append(cropX).append(",").append(cropY).append(")\n");
                    receipt.append("Then rotated 90\u00B0 CW to portrait\n");
                }
                receipt.append("Actual saved: ").append(dw).append("x").append(dh)
                    .append(" (").append(String.format(Locale.US, "%.1f", mp)).append(" MP)\n");
                receipt.append("Orientation correct: ").append(orientCorrect ? "YES" : "NO (MISMATCH!)").append("\n");
                receipt.append("File: ").append(savedFile != null ? savedFile.getAbsolutePath() : "SAVE FAILED").append("\n");
                receipt.append("Size: ").append(savedFile != null ?
                    String.format(Locale.US, "%,d bytes (%.2f MB)", savedFile.length(),
                        savedFile.length() / 1048576.0) : "?").append("\n");

                // Write EXIF
                if (savedFile != null) {
                    try {
                        ExifInterface exif = new ExifInterface(savedFile.getAbsolutePath());
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                            String.valueOf(ExifInterface.ORIENTATION_NORMAL));
                        exif.setAttribute(ExifInterface.TAG_MAKE, "INMO");
                        exif.setAttribute(ExifInterface.TAG_MODEL, "Air3 IMA301");
                        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FlashCam-Air3 v1.5");
                        exif.saveAttributes();
                    } catch (Exception ignored) {}
                }

                if (maxRes && mp >= 11.5) {
                    receipt.append("VERDICT: FULL-RES CAPTURE CONFIRMED!\n");
                } else if (orientCorrect) {
                    receipt.append("VERDICT: Capture OK\n");
                } else {
                    receipt.append("VERDICT: WARNING - orientation mismatch\n");
                }
            } else {
                receipt.append("\u2500\u2500 JPEG: NO DATA \u2500\u2500\n");
            }

            // Save DNG
            if (rawImage[0] != null && capResultHolder[0] != null && camChars != null) {
                setStatusForced("Processing DNG...");
                String dname = "FlashCam_" + ts + "_" + mpLabel + ".dng";
                try {
                    DngCreator dngCreator = new DngCreator(camChars, capResultHolder[0]);
                    dngCreator.setDescription("FlashCam-Air3 v1.5 Max-Res");

                    // DNG orientation: for landscape, set based on baseRotation.
                    // For portrait, the DNG is the raw sensor data — set orientation tag.
                    int dngExifOrientation;
                    if (currentOrient == OrientMode.PORTRAIT) {
                        // Raw data is unrotated sensor output. Tag tells viewers how to rotate.
                        int combined = (baseRotation + 90) % 360;
                        switch (combined) {
                            case 90:  dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_90; break;
                            case 180: dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_180; break;
                            case 270: dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_270; break;
                            default:  dngExifOrientation = ExifInterface.ORIENTATION_NORMAL; break;
                        }
                    } else {
                        switch (baseRotation) {
                            case 90:  dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_90; break;
                            case 180: dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_180; break;
                            case 270: dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_270; break;
                            default:  dngExifOrientation = ExifInterface.ORIENTATION_NORMAL; break;
                        }
                    }
                    dngCreator.setOrientation(dngExifOrientation);

                    File dngFile = saveDngToMediaStore(dngCreator, rawImage[0], dname);

                    receipt.append("\u2500\u2500 DNG \u2500\u2500\n");
                    receipt.append("Actual: ").append(dims[1][0]).append("x").append(dims[1][1]).append("\n");
                    receipt.append("File: ").append(dngFile != null ? dngFile.getAbsolutePath() : "SAVE FAILED").append("\n");
                    receipt.append("Size: ").append(dngFile != null ?
                        String.format(Locale.US, "%,d bytes (%.2f MB)", dngFile.length(),
                            dngFile.length() / 1048576.0) : "?").append("\n");
                    receipt.append("DNG orientation tag: ").append(dngExifOrientation).append("\n");

                    dngCreator.close();
                } catch (Exception dngErr) {
                    receipt.append("\u2500\u2500 DNG ERROR: ").append(dngErr.getMessage()).append(" \u2500\u2500\n");
                } finally {
                    rawImage[0].close();
                }
            } else if (rawImage[0] != null) {
                rawImage[0].close();
            }

            receipt.append("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\n");

            lastReceipt = receipt.toString();
            synchronized (receiptLog) {
                receiptLog.add(lastReceipt);
                while (receiptLog.size() > 50) receiptLog.remove(0);
            }

            mainHandler.post(() -> {
                if (debugEnabled) {
                    tvReceipt.setText(lastReceipt);
                    receiptPanel.setVisibility(View.VISIBLE);
                }
            });

            setStatusForced("Saved! " + mpLabel);

        } catch (Exception e) {
            finishCapture("Error: " + e.getMessage());
            return;
        } finally {
            if (jpegReader != null) try { jpegReader.close(); } catch (Exception ignored) {}
            if (rawReader != null) try { rawReader.close(); } catch (Exception ignored) {}
        }

        capturing = false;
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        startPreview();
        mainHandler.post(() -> btnShutter.setEnabled(true));
    }

    private void finishCapture(String msg) {
        setStatusForced(msg);
        capturing = false;
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        if (cameraDevice != null) startPreview();
        mainHandler.post(() -> btnShutter.setEnabled(true));
    }

    // ================================================================
    // VIDEO RECORDING
    // ================================================================
    private void startRecording() {
        if (cameraDevice == null || isRecording) return;

        workerHandler.post(() -> {
            try {
                if (previewSession != null) {
                    previewSession.close(); previewSession = null;
                    Thread.sleep(200);
                }

                int width, height;
                if (currentVideo == VideoMode.V4K && has4K) {
                    width = 3840; height = 2160;
                } else {
                    width = 1920; height = 1080;
                }

                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String orientLabel = (currentOrient == OrientMode.PORTRAIT) ? "port" : "land";
                String resLabel = (currentVideo == VideoMode.V4K) ? "4k" : "1080p";
                String filename = "FlashCamVID_" + ts + "_" + resLabel + "_" + orientLabel + ".mp4";

                File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "FlashCam-Air3");
                if (!dir.exists()) dir.mkdirs();
                File videoFile = new File(dir, filename);
                currentVideoPath = videoFile.getAbsolutePath();

                mediaRecorder = new MediaRecorder();
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setOutputFile(currentVideoPath);
                mediaRecorder.setVideoEncodingBitRate(
                    currentVideo == VideoMode.V4K ? 48_000_000 : 16_000_000);
                mediaRecorder.setVideoFrameRate(30);
                mediaRecorder.setVideoSize(width, height);
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(128_000);
                mediaRecorder.setAudioSamplingRate(44100);

                // Orientation hint for video
                int orientHint = getBaseRotation();
                if (currentOrient == OrientMode.PORTRAIT) {
                    orientHint = (orientHint + 90) % 360;
                }
                mediaRecorder.setOrientationHint(orientHint);

                mediaRecorder.prepare();

                // Create session with preview + recorder
                SurfaceTexture st = textureView.getSurfaceTexture();
                Size ps = previewSize != null ? previewSize : new Size(1440, 1080);
                st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
                Surface previewSurface = new Surface(st);
                Surface recorderSurface = mediaRecorder.getSurface();

                CaptureRequest.Builder recBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                recBuilder.addTarget(previewSurface);
                recBuilder.addTarget(recorderSurface);
                recBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                recBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv);

                List<OutputConfiguration> outputs = Arrays.asList(
                    new OutputConfiguration(previewSurface),
                    new OutputConfiguration(recorderSurface)
                );

                Executor vidExec = camHandler::post;
                final Object sessLock = new Object();
                final int[] sessResult = {-999};

                SessionConfiguration vidSessConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outputs, vidExec,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                            previewSession = session;
                            synchronized (sessLock) { sessResult[0] = 0; sessLock.notifyAll(); }
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            synchronized (sessLock) { sessResult[0] = -1; sessLock.notifyAll(); }
                        }
                    });

                cameraDevice.createCaptureSession(vidSessConfig);
                synchronized (sessLock) {
                    if (sessResult[0] == -999) sessLock.wait(30_000);
                }

                if (sessResult[0] != 0) {
                    transitionState(CamState.ERROR);
                    return;
                }

                previewSession.setRepeatingRequest(recBuilder.build(), null, camHandler);
                mediaRecorder.start();
                isRecording = true;

                transitionState(CamState.RECORDING);
                mainHandler.post(() -> {
                    tvRecTimer.setVisibility(View.VISIBLE);
                    tvRecTimer.setText("\u25CF REC");
                    tvRecTimer.setTextColor(COLOR_RED);
                    chronoRec.setVisibility(View.VISIBLE);
                    chronoRec.setBase(SystemClock.elapsedRealtime());
                    chronoRec.start();
                    btnShutter.setBackground(getDrawable(R.drawable.record_button));
                });

            } catch (Exception e) {
                setStatusForced("Record error: " + e.getMessage());
                isRecording = false;
            }
        });
    }

    private void stopRecording() {
        if (!isRecording) return;

        workerHandler.post(() -> {
            try {
                isRecording = false;
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;

                mainHandler.post(() -> {
                    chronoRec.stop();
                    chronoRec.setVisibility(View.GONE);
                    tvRecTimer.setVisibility(View.GONE);
                    btnShutter.setBackground(getDrawable(R.drawable.shutter_button));
                });

                if (currentVideoPath != null) {
                    MediaScannerConnection.scanFile(this,
                        new String[]{currentVideoPath}, new String[]{"video/mp4"}, null);
                }

                setStatusForced("Video saved!");

                if (debugEnabled) {
                    File vf = new File(currentVideoPath);
                    String receipt = "\u2550\u2550\u2550 VIDEO RECEIPT \u2550\u2550\u2550\n"
                        + "File: " + currentVideoPath + "\n"
                        + "Size: " + String.format(Locale.US, "%,d bytes (%.2f MB)",
                            vf.length(), vf.length() / 1048576.0) + "\n"
                        + "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\n";
                    lastReceipt = receipt;
                    mainHandler.post(() -> {
                        tvReceipt.setText(receipt);
                        receiptPanel.setVisibility(View.VISIBLE);
                    });
                }

            } catch (Exception e) {
                setStatusForced("Stop error: " + e.getMessage());
            }

            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            startPreview();
        });
    }

    // ================================================================
    // MEDIASTORE SAVING (scoped storage safe)
    // ================================================================
    private File saveToMediaStore(byte[] data, String filename, String mimeType) {
        // Try MediaStore first (scoped storage safe)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/FlashCam-Air3");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        out.write(data);
                        out.close();
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);

                    // Return a File reference for receipt (path may not be directly accessible)
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "FlashCam-Air3");
                    return new File(dir, filename);
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaStore save failed, falling back: " + e.getMessage());
            }
        }

        // Fallback: direct file write
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "FlashCam-Air3");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();
            MediaScannerConnection.scanFile(this,
                new String[]{file.getAbsolutePath()}, new String[]{mimeType}, null);
            return file;
        } catch (Exception e) {
            setStatusForced("Save error: " + e.getMessage());
            return null;
        }
    }

    private File saveDngToMediaStore(DngCreator dngCreator, Image rawImage, String filename) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/FlashCam-Air3");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        dngCreator.writeImage(out, rawImage);
                        out.close();
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);

                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "FlashCam-Air3");
                    return new File(dir, filename);
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaStore DNG save failed, falling back: " + e.getMessage());
            }
        }

        // Fallback
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "FlashCam-Air3");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, filename);
            OutputStream out = new FileOutputStream(file);
            dngCreator.writeImage(out, rawImage);
            out.close();
            MediaScannerConnection.scanFile(this,
                new String[]{file.getAbsolutePath()}, new String[]{"image/x-adobe-dng"}, null);
            return file;
        } catch (Exception e) {
            setStatusForced("DNG save error: " + e.getMessage());
            return null;
        }
    }

    // ================================================================
    // RECEIPT / LOG
    // ================================================================
    private void copyReceipt() {
        if (lastReceipt.isEmpty()) {
            Toast.makeText(this, "No receipt yet", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText("FlashCam Receipt", lastReceipt));
            Toast.makeText(this, "Receipt copied!", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportLog() {
        synchronized (receiptLog) {
            if (receiptLog.isEmpty()) {
                Toast.makeText(this, "No captures yet", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "FlashCam-Air3");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "flashcam_log.txt");
                FileWriter w = new FileWriter(f, false);
                w.write("FlashCam-Air3 v1.5 Log \u2014 " + receiptLog.size() + " captures\n");
                w.write("Exported: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.US).format(new Date()) + "\n\n");
                for (String r : receiptLog) { w.write(r); w.write("\n"); }
                w.close();
                Toast.makeText(this, "Log saved: " + f.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                Toast.makeText(this, "Export failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    // ================================================================
    // UTILITY
    // ================================================================
    private String fmtSize(Size s) {
        return s != null ? s.getWidth() + "x" + s.getHeight() : "?";
    }

    private Size findLargest(Size[] sizes) {
        Size best = sizes[0];
        long bestPx = (long) best.getWidth() * best.getHeight();
        for (Size s : sizes) {
            long px = (long) s.getWidth() * s.getHeight();
            if (px > bestPx) { best = s; bestPx = px; }
        }
        return best;
    }

    private Size findBest43Preview(Size[] sizes) {
        Size best43 = null;
        long best43Px = 0;
        Size bestAny = null;
        long bestAnyPx = 0;
        for (Size s : sizes) {
            int w = s.getWidth(), h = s.getHeight();
            long px = (long) w * h;
            float ratio = (float) w / h;
            boolean is43 = Math.abs(ratio - 4f / 3f) < 0.02f;
            if (is43 && px <= 1920L * 1440 && px > best43Px) { best43 = s; best43Px = px; }
            if (px <= 1920L * 1440 && px > bestAnyPx) { bestAny = s; bestAnyPx = px; }
        }
        return best43 != null ? best43 : (bestAny != null ? bestAny : sizes[0]);
    }
}
