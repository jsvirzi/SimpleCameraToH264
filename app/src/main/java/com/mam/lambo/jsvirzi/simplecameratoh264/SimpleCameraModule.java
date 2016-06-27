package com.mam.lambo.jsvirzi.simplecameratoh264;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by jsvirzi on 6/25/16.
 */
public class SimpleCameraModule {

    static String TAG = "SimpleCameraModule";
    static String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    static MediaFormat mediaFormat;
    static MediaFormat outputMediaFormat;
    private static final int IFrameInterval = 5;
    private MediaCodec encoder;
    private static final int MaxImages = 8;
    private static final int ImageWidth = 1920;
    private static final int ImageHeight = 1080;
    private static final int FrameRate = 15;
    private static final int BitRate = 6000000;
    public static Context context = null;
    private String cameraId;
    CameraCaptureSession captureSession = null;
    private CameraDevice device = null;
    private final int SurfaceH264 = 0;
    private final int SurfaceJpeg = 1;
    private final int MaxSurfaces = 2;
    private Surface[] surfaces = new Surface[MaxSurfaces];
    private ImageReader imageReader = null;
    private int frameCounter = 0;
    private long lastJpegTime = 0;
    private long lastH264Time = 0;

    public static void setApplicationContext(Context applicationContext) {
        context = applicationContext;
    }

    public SimpleCameraModule(Context context, String cameraId) {
        this.context = context;
        this.cameraId = cameraId;
        prepareEncoder(ImageWidth, ImageHeight, BitRate, FrameRate);
        prepareCamera(cameraId, ImageWidth, ImageHeight, FrameRate);
    }

    public void release() {

        if (captureSession != null) {
            captureSession.close();
        }

        if (encoder != null) {
            encoder.stop();
            encoder.release();
        }
    }

    public void prepareCamera(String cameraId, int width, int height, int frameRate) {

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, null);
        } catch (CameraAccessException ex) {
            ex.printStackTrace();
        }

    }

    public void prepareEncoder(int width, int height, int bitRate, int frameRate) {

        mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFrameInterval);
        Log.d(TAG, "format: " + mediaFormat);

        String codecName = MediaFormat.MIMETYPE_VIDEO_AVC;
        try {
            encoder = MediaCodec.createEncoderByType(codecName);
            if (encoder == null) {
                Log.wtf(TAG, "unable to initialize media codec");
                encoder = null;
                return;
            }
        } catch (IOException ex) {
            Log.wtf(TAG, "unable to create MediaCodec by name = " + codecName);
            ex.printStackTrace();
            encoder = null;
            return;
        }

        try {
            encoder.setCallback(mediaCodecCallback);
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surfaces[SurfaceH264] = encoder.createInputSurface();
            encoder.start();
        } catch (MediaCodec.CodecException ex) {
            Log.e(TAG, "MediaCodec exception caught. resetting...");
        } catch (IllegalStateException ex) {
            Log.e(TAG, "mediaCodec in funky state. resetting...");
        }

    }

    private MediaCodec.Callback mediaCodecCallback = new MediaCodec.Callback() {

        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            Log.d(TAG, "onInputBufferAvailable() called");
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            final long now = System.currentTimeMillis();
            long timeBetweenFrames = now - lastH264Time;
            lastH264Time = now;
            String msg = String.format("%s onOutputBufferAvailable() called. deltaT = %dms", cameraId, timeBetweenFrames);
            Log.d(TAG, msg);
            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException ex) {
            boolean isRecoverable = ex.isRecoverable(); // what TODO
            Log.e(TAG, "MediaCodec.onError() called. Will attempt to restart encoder");
            Log.e(TAG, ex.getDiagnosticInfo());
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            // capture output format for later use in making MP4
            if (outputMediaFormat == null) {
                outputMediaFormat = format;
            }
            Log.d(TAG, "MediaCodec.onOutputFormatChanged() called");
        }
    };

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(final CameraDevice cameraDevice) {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            try {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
                int[] modes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            } catch (CameraAccessException ex) {
                ex.printStackTrace();
            }

            imageReader = ImageReader.newInstance(ImageWidth, ImageHeight, ImageFormat.JPEG, MaxImages);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, null);
            surfaces[SurfaceJpeg] = imageReader.getSurface();

            List<Surface> surfaceList = new LinkedList<>();
            for (int i = 0; i < surfaces.length; i++) {
                surfaceList.add(surfaces[i]);
            }
            device = cameraDevice;
            try {
                cameraDevice.createCaptureSession(surfaceList, new CameraCaptureSession.StateCallback() {

                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        CameraCaptureSession.CaptureCallback captureCallback = null;
                        CaptureRequest.Builder captureRequestBuilder = null;
                        captureSession = session;

                        try {
                            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            int mode = captureRequestBuilder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
                            captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                            for (int i = 0; i < surfaces.length; i++) {
                                captureRequestBuilder.addTarget(surfaces[i]);
                            }
                            session.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, null);
                        } catch (CameraAccessException ex) {
                            ex.printStackTrace();
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                    }

                    @Override
                    public void onSurfacePrepared(CameraCaptureSession session, Surface surface) {
                    }

                }, null);
            } catch (CameraAccessException ex) {
                ex.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            //    http://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback.html
            String msg = String.format("onError(error=%d) called for FRONT camera", error);
            Log.d(TAG, msg);
            if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) {
                msg = String.format("ERROR: camera is in use");
            } else if (error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) {
                msg = String.format("ERROR: maximum cameras in use");
            } else if (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) {
                msg = String.format("ERROR: camera disabled");
            } else if (error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE) {
                msg = String.format("ERROR: camera device");
            } else if (error == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE) {
                msg = String.format("ERROR: camera service");
            } else {
                msg = String.format("ERROR: unclassified");
            }
            Log.d(TAG, msg);
            try {
                camera.close();
                //            } catch (CameraAccessException ex) {
                //                Log.d(TAG, "caught CameraAccessException for FRONT camera");
            } catch (IllegalStateException ex) {
                Log.d(TAG, "caught IllegalStateException for FRONT camera");
            }
        }
    };

    private final ImageReader.OnImageAvailableListener onImageAvailableListener =
        new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader imageReader) {
            final long start0 = System.currentTimeMillis();
            Image image = imageReader.acquireLatestImage();
            String msg;
            if (image != null) {
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer byteBuffer = planes[0].getBuffer();
                final int jpegSize = byteBuffer.limit();
                long timestamp = image.getTimestamp();
                image.close();
                ++frameCounter;
                long delta0 = System.currentTimeMillis() - start0;
                long timeBetweenFrames = (timestamp - lastJpegTime) / 1000000L; // convert to millisecs
                lastJpegTime = timestamp;
                msg = String.format("%s image processing time = %dms. jpeg size = %d. deltat = %dms",
                    cameraId, delta0, jpegSize, timeBetweenFrames);
                Log.d(TAG, msg);
            }
        }
    };

    public static String getCameraId(String identifier) {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String[] cameraIdList;
        try {
            cameraIdList = cameraManager.getCameraIdList();
        } catch (CameraAccessException ex) {
            ex.printStackTrace();
            return null;
        }

        for (final String cameraId : cameraIdList) {
            CameraCharacteristics characteristics;
            try {
                characteristics = cameraManager.getCameraCharacteristics(cameraId);
            } catch (CameraAccessException ex) {
                ex.printStackTrace();
                return null;
            }
            int cameraOrientation = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (cameraOrientation == CameraCharacteristics.LENS_FACING_FRONT && identifier.equals("internal")) {
                return cameraId;
            } else if (cameraOrientation == CameraCharacteristics.LENS_FACING_BACK && identifier.equals("external")) {
                return cameraId;
            }
        }
        return null;
    }

}
