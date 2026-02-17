package com.flashcam.air3;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.DngCreator;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FlashCam";
    private static final int PERM_CODE = 100;
    private static final int COLOR_ORANGE = 0xFFFF6600;
    private static final String APP_VERSION = "1.6.1";

    // ── Enums ──
    enum MpMode { MP8, MP12, MP16 }
    enum CamState { INIT, OPENING, PREVIEW, CAPTURING, ERROR }

    // ── State ──
    private MpMode currentMp = MpMode.MP16;
    private boolean dngEnabled = false;
    private boolean debugEnabled = false;
    private boolean capturing = false;
    private int currentEv = 0;

    // ── Camera ──
    private CameraManager camManager;
    private CameraDevice cameraDevice;
    private CameraCharacteristics camChars;
    private CameraCaptureSession previewSession;
    private String cameraId;
    private int sensorOrientation = 0;

    // ── Sizes ──
    private Size previewSize;
    private Size[] defaultJpegSizes;
    private Size[] maxResJpegSizes;
    private Size[] maxResRawSizes;
    private boolean hasMaxRes = false;

    // ── Threads ──
    private HandlerThread camThread;
    private Handler camHandler;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── UI ──
    private TextureView textureView;
    private View shutterFlashOverlay;
    private View focusRing;
    private TextView tvStatus, tvMode, tvFocusIndicator, tvEv;
    private TextView tvReceipt;
    private ImageButton btnShutter;
    private Button btnMode, btnDng, btnDebug, btnCredits;
    private Button btnEvPlus, btnEvMinus;
    private Button btnCopyReceipt, btnExportLog, btnDismiss;
    private LinearLayout receiptPanel;

    // ── State machine ──
    private CamState camState = CamState.INIT;
    private long lastStatusUpdate = 0;
    private static final long STATUS_THROTTLE_MS = 300;

    // ── Receipt log ──
    private String lastReceipt = "";
    private final List<String> receiptLog = new ArrayList<>();

    // ================================================================
    // LIFECYCLE
    // ================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bindViews();
        setupListeners();

        camThread = new HandlerThread("CamThread");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        workerThread = new HandlerThread("WorkerThread");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (textureView.isAvailable() && cameraDevice == null) {
            workerHandler.post(this::initCamera);
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
        if (camThread != null) { camThread.quitSafely(); }
        if (workerThread != null) { workerThread.quitSafely(); }
    }

    // ================================================================
    // VIEW BINDING & LISTENERS
    // ================================================================
    private void bindViews() {
        textureView = findViewById(R.id.textureView);
        shutterFlashOverlay = findViewById(R.id.shutterFlashOverlay);
        focusRing = findViewById(R.id.focusRing);
        tvStatus = findViewById(R.id.tvStatus);
        tvMode = findViewById(R.id.tvMode);
        tvFocusIndicator = findViewById(R.id.tvFocusIndicator);
        tvEv = findViewById(R.id.tvEv);
        tvReceipt = findViewById(R.id.tvReceipt);
        btnShutter = findViewById(R.id.btnShutter);
        btnMode = findViewById(R.id.btnMode);
        btnDng = findViewById(R.id.btnDng);
        btnDebug = findViewById(R.id.btnDebug);
        btnCredits = findViewById(R.id.btnCredits);
        btnEvPlus = findViewById(R.id.btnEvPlus);
        btnEvMinus = findViewById(R.id.btnEvMinus);
        btnCopyReceipt = findViewById(R.id.btnCopyReceipt);
        btnExportLog = findViewById(R.id.btnExportLog);
        btnDismiss = findViewById(R.id.btnDismiss);
        receiptPanel = findViewById(R.id.receiptPanel);
    }

    private void setupListeners() {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                configurePreviewTransform(w, h);
                if (cameraDevice == null) workerHandler.post(() -> initCamera());
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                configurePreviewTransform(w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });

        // Tap-to-focus (full-frame, no crop filtering)
        textureView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN && previewSession != null && !capturing) {
                handleTapToFocus(event.getX(), event.getY());
            }
            return true;
        });

        btnShutter.setOnClickListener(v -> {
            if (!capturing) {
                capturing = true;
                btnShutter.setEnabled(false);
                triggerShutterFlash();
                workerHandler.post(this::doCapture);
            }
        });

        // MP mode: cycle 8 → 12 → 16
        btnMode.setOnClickListener(v -> {
            switch (currentMp) {
                case MP8:  currentMp = MpMode.MP12; break;
                case MP12: currentMp = MpMode.MP16; break;
                case MP16: currentMp = MpMode.MP8;  break;
            }
            updateModeDisplay();
        });

        btnDng.setOnClickListener(v -> {
            dngEnabled = !dngEnabled;
            btnDng.setText(dngEnabled ? "DNG:ON" : "DNG:OFF");
            btnDng.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                dngEnabled ? COLOR_ORANGE : 0xFF333333));
        });

        btnEvPlus.setOnClickListener(v -> adjustEv(1));
        btnEvMinus.setOnClickListener(v -> adjustEv(-1));

        btnDebug.setOnClickListener(v -> {
            debugEnabled = !debugEnabled;
            btnDebug.setText(debugEnabled ? "DBG:ON" : "DBG:OFF");
            if (!debugEnabled) receiptPanel.setVisibility(View.GONE);
        });

        btnCredits.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("FlashCam Air3 v" + APP_VERSION)
                .setMessage("Full-resolution photo camera for INMO Air3.\n\n"
                    + "Photo-only app \u2014 video is intentionally disabled to prevent "
                    + "dangerous heat buildup on the Air3.\n\n"
                    + "Developed with assistance from Manus AI and ChatGPT (OpenAI).\n\n"
                    + "Unlocks 16MP (4608\u00D73456) JPEG and 16.3MP (4656\u00D73496) RAW/DNG "
                    + "capture via Camera2 SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION.\n\n"
                    + "No root required. Public Camera2 API only.")
                .setPositiveButton("OK", null)
                .show();
        });

        btnCopyReceipt.setOnClickListener(v -> copyReceipt());
        btnExportLog.setOnClickListener(v -> exportLog());
        btnDismiss.setOnClickListener(v -> receiptPanel.setVisibility(View.GONE));
    }

    // ================================================================
    // PERMISSIONS
    // ================================================================
    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT < 29) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                missing.add(p);
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERM_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_CODE) {
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    setStatusForced("Camera permission required");
                    return;
                }
            }
            if (textureView.isAvailable()) workerHandler.post(this::initCamera);
        }
    }

    // ================================================================
    // CAMERA INIT
    // ================================================================
    private void initCamera() {
        try {
            transitionState(CamState.INIT);
            String[] ids = camManager.getCameraIdList();
            cameraId = null;

            for (String id : ids) {
                CameraCharacteristics cc = camManager.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    camChars = cc;
                    break;
                }
            }
            if (cameraId == null && ids.length > 0) {
                cameraId = ids[0];
                camChars = camManager.getCameraCharacteristics(cameraId);
            }
            if (cameraId == null) { setStatusForced("No camera found"); return; }

            Integer so = camChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = (so != null) ? so : 0;

            // Default stream map
            StreamConfigurationMap defaultMap = camChars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (defaultMap != null) {
                defaultJpegSizes = defaultMap.getOutputSizes(ImageFormat.JPEG);
                previewSize = findBest43Preview(defaultMap.getOutputSizes(SurfaceTexture.class));
            }

            // Max-res stream map (API 31+)
            try {
                StreamConfigurationMap maxResMap = camChars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                if (maxResMap != null) {
                    maxResJpegSizes = maxResMap.getOutputSizes(ImageFormat.JPEG);
                    maxResRawSizes = maxResMap.getOutputSizes(ImageFormat.RAW_SENSOR);
                    hasMaxRes = (maxResJpegSizes != null && maxResJpegSizes.length > 0);
                }
            } catch (Exception e) {
                Log.w(TAG, "Max-res map not available: " + e.getMessage());
            }

            openCamera();

        } catch (Exception e) {
            setStatusForced("Init error: " + e.getMessage());
        }
    }

    private void openCamera() {
        transitionState(CamState.OPENING);

        // Register availability callback
        camManager.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
            @Override public void onCameraAvailable(@NonNull String id) {
                Log.d(TAG, "Camera " + id + " available");
            }
            @Override public void onCameraUnavailable(@NonNull String id) {
                Log.d(TAG, "Camera " + id + " unavailable");
            }
        }, camHandler);

        // Retry logic
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    setStatusForced("Camera permission not granted");
                    return;
                }

                final Object openLock = new Object();
                final int[] openResult = {-999};

                Executor camExec = camHandler::post;
                camManager.openCamera(cameraId, camExec, new CameraDevice.StateCallback() {
                    @Override public void onOpened(@NonNull CameraDevice camera) {
                        cameraDevice = camera;
                        synchronized (openLock) { openResult[0] = 0; openLock.notifyAll(); }
                    }
                    @Override public void onDisconnected(@NonNull CameraDevice camera) {
                        camera.close(); cameraDevice = null;
                        synchronized (openLock) { openResult[0] = -2; openLock.notifyAll(); }
                    }
                    @Override public void onError(@NonNull CameraDevice camera, int error) {
                        camera.close(); cameraDevice = null;
                        synchronized (openLock) { openResult[0] = error; openLock.notifyAll(); }
                    }
                });

                synchronized (openLock) {
                    if (openResult[0] == -999) openLock.wait(60_000);
                }

                if (openResult[0] == 0) {
                    startPreview();
                    return;
                }

                Log.w(TAG, "Camera open attempt " + attempt + " failed: " + openResult[0]);
                Thread.sleep(2000);

            } catch (Exception e) {
                Log.w(TAG, "Camera open attempt " + attempt + " exception: " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        transitionState(CamState.ERROR);
        setStatusForced("Camera open failed after 3 attempts");
    }

    private void closeCamera() {
        try {
            if (previewSession != null) { previewSession.close(); previewSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        } catch (Exception ignored) {}
    }

    // ================================================================
    // PREVIEW
    // ================================================================
    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) return;

        try {
            if (previewSession != null) { previewSession.close(); previewSession = null; }

            SurfaceTexture st = textureView.getSurfaceTexture();
            Size ps = previewSize != null ? previewSize : new Size(1440, 1080);
            st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
            Surface previewSurface = new Surface(st);

            CaptureRequest.Builder previewBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON);
            previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv);

            List<OutputConfiguration> outputs = Arrays.asList(new OutputConfiguration(previewSurface));
            Executor prevExec = camHandler::post;

            final Object sessLock = new Object();
            final int[] sessResult = {-999};

            SessionConfiguration sessConfig = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, prevExec,
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        previewSession = session;
                        synchronized (sessLock) { sessResult[0] = 0; sessLock.notifyAll(); }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        synchronized (sessLock) { sessResult[0] = -1; sessLock.notifyAll(); }
                    }
                });

            cameraDevice.createCaptureSession(sessConfig);
            synchronized (sessLock) {
                if (sessResult[0] == -999) sessLock.wait(30_000);
            }

            if (sessResult[0] != 0) {
                transitionState(CamState.ERROR);
                return;
            }

            previewSession.setRepeatingRequest(previewBuilder.build(),
                new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                            @NonNull CaptureRequest r, @NonNull TotalCaptureResult result) {
                        updateFocusIndicator(result);
                    }
                }, camHandler);

            transitionState(CamState.PREVIEW);
            mainHandler.post(() -> {
                btnShutter.setEnabled(true);
                configurePreviewTransform(textureView.getWidth(), textureView.getHeight());
                updateModeDisplay();
            });

        } catch (Exception e) {
            setStatusForced("Preview error: " + e.getMessage());
            transitionState(CamState.ERROR);
        }
    }

    // ================================================================
    // ROTATION HELPERS
    // ================================================================

    /**
     * Rotation to apply to JPEG pixel data from the ImageReader so the saved
     * image appears upright.
     *
     * The JPEG from ImageReader arrives in the sensor's native orientation.
     * On the Air3 (sensorOrientation=270, landscape-locked), the sensor buffer
     * needs to be rotated by sensorOrientation degrees to appear upright.
     *
     * Evidence-based: v1.6.0 applied 90° and got 180° upside down.
     * Correct value is sensorOrientation (270°) for the Air3.
     */
    private int getJpegRotationDegrees() {
        return sensorOrientation;
    }

    // ================================================================
    // PREVIEW TRANSFORM (FIT, NO ROTATION)
    // ================================================================

    /**
     * Preview transform: fit the 4:3 preview buffer into the view with
     * uniform scaling (letterbox). NO rotation is applied.
     *
     * Evidence-based: the TextureView on the Air3 already displays the
     * preview buffer in the correct orientation. v1.6.0 applied 90° rotation
     * to the preview and it appeared 90° CW from correct. Therefore the
     * correct preview rotation is 0°.
     *
     * The TextureView by default stretches the buffer to fill the view.
     * We undo that stretch and apply uniform fit-scale instead.
     */
    private void configurePreviewTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) return;

        int pw = previewSize.getWidth();  // e.g., 1440
        int ph = previewSize.getHeight(); // e.g., 1080

        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        // No rotation — preview buffer is already correctly oriented on the Air3.
        // Just need to undo the default stretch and apply uniform fit scale.

        // Uniform fit scale: fit pw×ph into viewWidth×viewHeight
        float fitScaleX = (float) viewWidth / pw;
        float fitScaleY = (float) viewHeight / ph;
        float fitScale = Math.min(fitScaleX, fitScaleY);

        Matrix matrix = new Matrix();

        // Step 1: Translate center to origin
        matrix.setTranslate(-centerX, -centerY);

        // Step 2: Undo the default stretch (TextureView maps pw→viewWidth, ph→viewHeight)
        matrix.postScale((float) pw / viewWidth, (float) ph / viewHeight);

        // Step 3: Apply uniform fit scale (no rotation)
        matrix.postScale(fitScale, fitScale);

        // Step 4: Translate back to view center
        matrix.postTranslate(centerX, centerY);

        textureView.setTransform(matrix);
    }

    // ================================================================
    // TAP-TO-FOCUS
    // ================================================================
    private void handleTapToFocus(float tx, float ty) {
        if (previewSession == null || cameraDevice == null || camChars == null) return;

        // Show focus ring
        mainHandler.post(() -> {
            focusRing.setX(tx - 30);
            focusRing.setY(ty - 30);
            focusRing.setVisibility(View.VISIBLE);
            focusRing.setAlpha(1f);
            focusRing.animate().alpha(0f).setDuration(1500).withEndAction(() ->
                focusRing.setVisibility(View.GONE)).start();
        });

        // Map tap to sensor coordinates [0..1]
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (vw == 0 || vh == 0) return;

        float nx = tx / vw;
        float ny = ty / vh;

        // Map to sensor active array
        android.graphics.Rect activeArray = camChars.get(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray == null) return;

        int aw = activeArray.width();
        int ah = activeArray.height();
        int regionSize = (int) (Math.max(aw, ah) * 0.1f);
        int cx = (int) (nx * aw);
        int cy = (int) (ny * ah);

        int left = Math.max(0, cx - regionSize / 2);
        int top = Math.max(0, cy - regionSize / 2);
        int right = Math.min(aw, left + regionSize);
        int bottom = Math.min(ah, top + regionSize);

        MeteringRectangle[] regions = new MeteringRectangle[]{
            new MeteringRectangle(left, top, right - left, bottom - top, 1000)
        };

        try {
            CaptureRequest.Builder afBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            Size ps = previewSize != null ? previewSize : new Size(1440, 1080);
            st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
            afBuilder.addTarget(new Surface(st));
            afBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            afBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            afBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
            afBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
            afBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv);

            previewSession.capture(afBuilder.build(), null, camHandler);
        } catch (Exception e) {
            Log.w(TAG, "Tap-to-focus error: " + e.getMessage());
        }
    }

    // ================================================================
    // JPEG PIXEL ROTATION
    // ================================================================

    /**
     * Rotate a JPEG byte array by the given degrees (must be 0, 90, 180, 270).
     * Returns the re-encoded JPEG at quality 100.
     */
    private byte[] rotateJpegPixels(byte[] jpegData, int degrees) {
        if (degrees == 0) return jpegData;
        Bitmap src = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (src == null) return jpegData;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, 100, bos);
        src.recycle();
        rotated.recycle();
        return bos.toByteArray();
    }

    // ================================================================
    // CAPTURE
    // ================================================================
    private void doCapture() {
        if (cameraDevice == null) { finishCapture("No camera"); return; }

        transitionState(CamState.CAPTURING);
        setStatusForced("Hold still...");

        try {
            // Close preview session first
            if (previewSession != null) {
                previewSession.close();
                previewSession = null;
                Thread.sleep(200);
            }

            // Determine capture size
            boolean maxRes = (currentMp == MpMode.MP16 || currentMp == MpMode.MP12) && hasMaxRes;
            Size jpegSize;
            Size rawSize = null;

            if (maxRes && maxResJpegSizes != null && maxResJpegSizes.length > 0) {
                jpegSize = findBestForMp(maxResJpegSizes, currentMp);
            } else if (defaultJpegSizes != null && defaultJpegSizes.length > 0) {
                jpegSize = findBestForMp(defaultJpegSizes, currentMp);
                maxRes = false;
            } else {
                finishCapture("No JPEG sizes available");
                return;
            }

            if (dngEnabled && maxRes && maxResRawSizes != null && maxResRawSizes.length > 0) {
                rawSize = findLargest(maxResRawSizes);
            }

            // Create ImageReaders
            ImageReader jpegReader = ImageReader.newInstance(
                jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 1);
            ImageReader rawReader = (rawSize != null) ?
                ImageReader.newInstance(rawSize.getWidth(), rawSize.getHeight(),
                    ImageFormat.RAW_SENSOR, 1) : null;

            final byte[][] jpegData = {null};
            final Image[] rawImage = {null};
            final Object imgLock = new Object();
            final int[][] dims = {{0, 0}, {0, 0}};

            jpegReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img != null) {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    jpegData[0] = new byte[buf.remaining()];
                    buf.get(jpegData[0]);
                    dims[0][0] = img.getWidth();
                    dims[0][1] = img.getHeight();
                    img.close();
                    synchronized (imgLock) { imgLock.notifyAll(); }
                }
            }, camHandler);

            if (rawReader != null) {
                rawReader.setOnImageAvailableListener(reader -> {
                    rawImage[0] = reader.acquireLatestImage();
                    if (rawImage[0] != null) {
                        dims[1][0] = rawImage[0].getWidth();
                        dims[1][1] = rawImage[0].getHeight();
                        synchronized (imgLock) { imgLock.notifyAll(); }
                    }
                }, camHandler);
            }

            // Build output configurations
            List<OutputConfiguration> outputs = new ArrayList<>();
            OutputConfiguration jpegOutput = new OutputConfiguration(jpegReader.getSurface());
            if (maxRes) {
                try { jpegOutput.getClass().getMethod("setSensorPixelModeUsed", int.class)
                        .invoke(jpegOutput, 1); }
                catch (Exception e) { Log.w(TAG, "setSensorPixelModeUsed JPEG failed: " + e.getMessage()); }
            }
            outputs.add(jpegOutput);

            OutputConfiguration rawOutput = null;
            if (rawReader != null) {
                rawOutput = new OutputConfiguration(rawReader.getSurface());
                if (maxRes) {
                    try { rawOutput.getClass().getMethod("setSensorPixelModeUsed", int.class)
                            .invoke(rawOutput, 1); }
                    catch (Exception e) { Log.w(TAG, "setSensorPixelModeUsed RAW failed: " + e.getMessage()); }
                }
                outputs.add(rawOutput);
            }

            // Create capture session
            Executor capExec = camHandler::post;
            final Object sessLock = new Object();
            final int[] sessResult = {-999};

            SessionConfiguration sessConfig = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, capExec,
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        synchronized (sessLock) { sessResult[0] = 0; sessLock.notifyAll(); }
                        previewSession = session;
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        synchronized (sessLock) { sessResult[0] = -1; sessLock.notifyAll(); }
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
                if (sessResult[0] == -999) sessLock.wait(30_000);
            }

            CameraCaptureSession session = previewSession;
            if (sessResult[0] != 0 || session == null) {
                finishCapture("Session config failed");
                return;
            }

            setStatusForced("Capturing...");

            // Build capture request
            CaptureRequest.Builder capBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            capBuilder.addTarget(jpegReader.getSurface());
            if (rawReader != null) capBuilder.addTarget(rawReader.getSurface());

            capBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            capBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            capBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv);

            // CRITICAL: Set JPEG_ORIENTATION to 0 — we do pixel rotation in software
            capBuilder.set(CaptureRequest.JPEG_ORIENTATION, 0);

            if (maxRes) {
                try {
                    CaptureRequest.Key<Integer> pixelModeKey =
                        new CaptureRequest.Key<>("android.sensor.pixelMode", Integer.class);
                    capBuilder.set(pixelModeKey, 1);
                } catch (Exception e) {
                    Log.w(TAG, "CaptureRequest pixelMode failed: " + e.getMessage());
                }
            }

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

            setStatusForced("Captured! Processing...");

            if (!capOk[0]) { session.close(); previewSession = null; finishCapture("Capture failed"); return; }

            synchronized (imgLock) { if (jpegData[0] == null) imgLock.wait(15_000); }
            if (rawReader != null && rawImage[0] == null) {
                synchronized (imgLock) { if (rawImage[0] == null) imgLock.wait(15_000); }
            }

            session.close();
            previewSession = null;

            // ── Process and save ──
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String mpLabel;
            switch (currentMp) {
                case MP8:  mpLabel = "8MP"; break;
                case MP12: mpLabel = "12MP"; break;
                default:   mpLabel = "16MP"; break;
            }

            int jpegRotDeg = getJpegRotationDegrees();

            StringBuilder receipt = new StringBuilder();
            receipt.append("\u2550\u2550\u2550 CAPTURE RECEIPT \u2550\u2550\u2550\n");
            receipt.append("Time: ").append(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
            receipt.append("Mode: ").append(mpLabel).append(maxRes ? " (MAX-RES)" : " (DEFAULT)").append("\n");
            receipt.append("sensorOrientation: ").append(sensorOrientation).append("\u00B0\n");
            receipt.append("JPEG pixel rotation: ").append(jpegRotDeg).append("\u00B0\n");
            receipt.append("JPEG_ORIENTATION sent: 0\u00B0 (pixel rotation in software)\n");
            receipt.append("EV: ").append((currentEv >= 0 ? "+" : "")).append(currentEv).append("\n");

            // Save JPEG
            if (jpegData[0] != null) {
                setStatusForced("Processing JPEG...");

                // Rotate pixels to upright using sensorOrientation
                byte[] finalJpeg = rotateJpegPixels(jpegData[0], jpegRotDeg);

                // Decode final dimensions
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(finalJpeg, 0, finalJpeg.length, opts);
                int dw = opts.outWidth, dh = opts.outHeight;
                double mp = (long) dw * dh / 1e6;

                // Sanity check: for a landscape-locked device, W should be >= H
                boolean orientCorrect = (dw >= dh);
                if (!orientCorrect) {
                    receipt.append("NOTE: W<H after rotation (").append(dw).append("x").append(dh)
                        .append("). Sensor may have unusual orientation.\n");
                }

                String jname = "FlashCam_" + ts + "_" + mpLabel + "_full.jpg";
                File savedFile = saveToMediaStore(finalJpeg, jname, "image/jpeg");

                receipt.append("\u2500\u2500 JPEG \u2500\u2500\n");
                receipt.append("Sensor raw: ").append(dims[0][0]).append("x").append(dims[0][1]).append("\n");
                receipt.append("Rotation applied: ").append(jpegRotDeg).append("\u00B0\n");
                receipt.append("Saved: ").append(dw).append("x").append(dh)
                    .append(" (").append(String.format(Locale.US, "%.1f", mp)).append(" MP)\n");
                receipt.append("File: ").append(savedFile != null ? savedFile.getAbsolutePath() : "SAVE FAILED").append("\n");
                receipt.append("Size: ").append(savedFile != null ?
                    String.format(Locale.US, "%,d bytes (%.2f MB)", savedFile.length(),
                        savedFile.length() / 1048576.0) : "?").append("\n");

                // Write EXIF — always ORIENTATION_NORMAL since pixels are already rotated
                if (savedFile != null) {
                    try {
                        ExifInterface exif = new ExifInterface(savedFile.getAbsolutePath());
                        exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                            String.valueOf(ExifInterface.ORIENTATION_NORMAL));
                        exif.setAttribute(ExifInterface.TAG_SOFTWARE,
                            "FlashCam-Air3 v" + APP_VERSION);
                        exif.saveAttributes();
                    } catch (Exception exifErr) {
                        receipt.append("EXIF write error: ").append(exifErr.getMessage()).append("\n");
                    }
                }

                if (maxRes && mp >= 11.5) {
                    receipt.append("VERDICT: FULL-RES CAPTURE CONFIRMED!\n");
                } else if (orientCorrect) {
                    receipt.append("VERDICT: Capture OK\n");
                } else {
                    receipt.append("VERDICT: Check orientation in Gallery\n");
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
                    dngCreator.setDescription("FlashCam-Air3 v" + APP_VERSION + " Max-Res");

                    // DNG stores raw sensor data — set orientation tag so viewers know how to rotate
                    int dngExifOrientation;
                    switch (sensorOrientation) {
                        case 90:  dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_90; break;
                        case 180: dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_180; break;
                        case 270: dngExifOrientation = ExifInterface.ORIENTATION_ROTATE_270; break;
                        default:  dngExifOrientation = ExifInterface.ORIENTATION_NORMAL; break;
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

            final boolean showReceipt = debugEnabled;
            mainHandler.post(() -> {
                if (showReceipt) {
                    tvReceipt.setText(lastReceipt);
                    receiptPanel.setVisibility(View.VISIBLE);
                }
            });

            setStatusForced("Saved! " + mpLabel);

        } catch (Exception e) {
            finishCapture("Error: " + e.getMessage());
            return;
        } finally {
            // ImageReaders are closed when they go out of scope via GC
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
    // MEDIASTORE SAVING (scoped storage safe)
    // ================================================================
    private File saveToMediaStore(byte[] data, String filename, String mimeType) {
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
    // UI HELPERS
    // ================================================================
    private void updateModeDisplay() {
        String mpText;
        switch (currentMp) {
            case MP8:  mpText = "8 MP"; break;
            case MP12: mpText = "12 MP"; break;
            default:   mpText = "16 MP"; break;
        }
        btnMode.setText(mpText);
        tvMode.setText(mpText + " FULL");
    }

    private void adjustEv(int delta) {
        currentEv += delta;
        // Clamp to range
        if (camChars != null) {
            android.util.Range<Integer> evRange = camChars.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (evRange != null) {
                currentEv = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), currentEv));
            }
        }
        tvEv.setText((currentEv >= 0 ? "+" : "") + currentEv + " EV");

        // Apply to current preview
        if (previewSession != null && cameraDevice != null && !capturing) {
            try {
                CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                SurfaceTexture st = textureView.getSurfaceTexture();
                if (st != null) {
                    Size ps = previewSize != null ? previewSize : new Size(1440, 1080);
                    st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
                    b.addTarget(new Surface(st));
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv);
                    previewSession.setRepeatingRequest(b.build(), null, camHandler);
                }
            } catch (Exception e) {
                Log.w(TAG, "EV adjust error: " + e.getMessage());
            }
        }
    }

    private void triggerShutterFlash() {
        shutterFlashOverlay.setBackgroundColor(0xCCFFFFFF);
        shutterFlashOverlay.setVisibility(View.VISIBLE);
        shutterFlashOverlay.setAlpha(1f);
        shutterFlashOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction(() -> {
                shutterFlashOverlay.setVisibility(View.GONE);
                shutterFlashOverlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            })
            .start();
    }

    private void updateFocusIndicator(TotalCaptureResult result) {
        Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);

        String afText = "AF:--";
        if (afState != null) {
            switch (afState) {
                case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED: afText = "AF:LOCK"; break;
                case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: afText = "AF:FAIL"; break;
                case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: afText = "AF:OK"; break;
                case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN: afText = "AF:SCAN"; break;
                case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN: afText = "AF:SCAN"; break;
                default: afText = "AF:" + afState; break;
            }
        }

        String aeText = "AE:--";
        if (aeState != null) {
            switch (aeState) {
                case CaptureResult.CONTROL_AE_STATE_CONVERGED: aeText = "AE:OK"; break;
                case CaptureResult.CONTROL_AE_STATE_SEARCHING: aeText = "AE:SCAN"; break;
                case CaptureResult.CONTROL_AE_STATE_LOCKED: aeText = "AE:LOCK"; break;
                case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: aeText = "AE:FLASH"; break;
                default: aeText = "AE:" + aeState; break;
            }
        }

        final String text = afText + " | " + aeText;
        mainHandler.post(() -> tvFocusIndicator.setText(text));
    }

    // ================================================================
    // STATE MACHINE (throttled status updates)
    // ================================================================
    private void transitionState(CamState newState) {
        camState = newState;
        String text;
        switch (newState) {
            case INIT:      text = "Initializing..."; break;
            case OPENING:   text = "Opening camera..."; break;
            case PREVIEW:   text = "Ready"; break;
            case CAPTURING: text = "Capturing..."; break;
            case ERROR:     text = "Error"; break;
            default:        text = ""; break;
        }
        setStatusForced(text);
    }

    private void setStatusForced(String text) {
        long now = System.currentTimeMillis();
        lastStatusUpdate = now;
        mainHandler.post(() -> tvStatus.setText(text));
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
                w.write("FlashCam-Air3 v" + APP_VERSION + " Log \u2014 " + receiptLog.size() + " captures\n");
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

    private Size findBestForMp(Size[] sizes, MpMode mp) {
        long targetPx;
        switch (mp) {
            case MP8:  targetPx = 8_000_000L; break;
            case MP12: targetPx = 12_000_000L; break;
            default:   targetPx = 16_000_000L; break;
        }

        Size best = sizes[0];
        long bestDiff = Math.abs((long) best.getWidth() * best.getHeight() - targetPx);
        for (Size s : sizes) {
            long px = (long) s.getWidth() * s.getHeight();
            long diff = Math.abs(px - targetPx);
            if (diff < bestDiff) { best = s; bestDiff = diff; }
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
