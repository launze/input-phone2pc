package com.voiceinput.cimbar;

import java.util.List;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import org.opencv.BuildConfig;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * This class is an implementation of the Bridge View between OpenCV and Java Camera.
 * This class relays on the functionality available in base class and only implements
 * required functions:
 * connectCamera - opens Java camera and sets the PreviewCallback to be delivered.
 * disconnectCamera - closes the camera and stops preview.
 * When frame is delivered via callback from Camera - it processed via OpenCV to be
 * converted to RGBA32 and then passed to the external callback for modifications if required.
 */
public class OpencvCameraView extends CameraBridgeViewBase implements PreviewCallback {

    private static final int MAGIC_TEXTURE_ID = 10;
    private static final String TAG = "JavaCameraView";
    private static int sTargetPreviewFps = 30;

    private byte mBuffer[];
    private Mat[] mFrameChain;
    private int mChainIdx = 0;
    private Thread mThread;
    private boolean mStopThread;

    protected Camera mCamera;
    protected JavaCameraFrame[] mCameraFrame;
    private SurfaceTexture mSurfaceTexture;
    private int mPreviewFormat = ImageFormat.NV21;
    private int mFrameRotation = 0;
    private int mOpenedCameraIndex = -1;

    public static class JavaCameraSizeAccessor implements ListItemAccessor {

        @Override
        public int getWidth(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.width;
        }

        @Override
        public int getHeight(Object obj) {
            Camera.Size size = (Camera.Size) obj;
            return size.height;
        }
    }

    private void applyBestPreviewFpsRange(Camera.Parameters params) {
        List<int[]> ranges = params.getSupportedPreviewFpsRange();
        if (ranges == null || ranges.isEmpty()) {
            return;
        }

        final int target = sTargetPreviewFps * 1000;
        int[] best = null;
        for (int[] range : ranges) {
            if (range == null || range.length < 2) {
                continue;
            }
            int min = range[0];
            int max = range[1];

            if (min == target && max == target) {
                best = range;
                break;
            }
            if (max >= target && (best == null || min > best[0] || (min == best[0] && max < best[1]))) {
                best = range;
            }
        }

        if (best == null) {
            for (int[] range : ranges) {
                if (range == null || range.length < 2) {
                    continue;
                }
                if (best == null || range[1] > best[1] || (range[1] == best[1] && range[0] > best[0])) {
                    best = range;
                }
            }
        }

        if (best != null) {
            params.setPreviewFpsRange(best[0], best[1]);
            Log.d(TAG, "Set preview FPS range to " + best[0] + "-" + best[1]);
        }
    }

    private void applyFastExposureControls(Camera.Parameters params) {
        List<String> sceneModes = params.getSupportedSceneModes();
        if (sceneModes != null && sceneModes.contains(Camera.Parameters.SCENE_MODE_SPORTS)) {
            params.setSceneMode(Camera.Parameters.SCENE_MODE_SPORTS);
            Log.d(TAG, "Set scene mode to sports");
        }

        int minExposure = params.getMinExposureCompensation();
        int maxExposure = params.getMaxExposureCompensation();
        float exposureStep = params.getExposureCompensationStep();
        if (minExposure < 0 && exposureStep > 0.0f) {
            int targetExposure = Math.round(-1.0f / exposureStep);
            targetExposure = Math.max(minExposure, Math.min(maxExposure, targetExposure));
            params.setExposureCompensation(targetExposure);
            Log.d(TAG, "Set exposure compensation to " + targetExposure + " step(s), step=" + exposureStep);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH && params.isAutoExposureLockSupported()) {
            params.setAutoExposureLock(false);
        }
    }

    public OpencvCameraView(Context context, int cameraId) {
        super(context, cameraId);
    }

    public OpencvCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public static void setTargetPreviewFps(int fps) {
        sTargetPreviewFps = Math.max(1, fps);
    }

    protected Size bestCameraFrameSize(List<?> supportedSizes, ListItemAccessor accessor, int surfaceWidth, int surfaceHeight) {
        int calcWidth = 10000000;
        int calcHeight = 10000000;

        int maxAllowedWidth = (mMaxWidth != MAX_UNSPECIFIED && mMaxWidth < surfaceWidth)? mMaxWidth : surfaceWidth;
        int maxAllowedHeight = (mMaxHeight != MAX_UNSPECIFIED && mMaxHeight < surfaceHeight)? mMaxHeight : surfaceHeight;

        for (Object size : supportedSizes) {
            int width = accessor.getWidth(size);
            int height = accessor.getHeight(size);
            int minDim = Math.min(width, height);
            if (minDim < 960 || minDim > 1080)
                continue;

            if (width <= maxAllowedWidth && height <= maxAllowedHeight) {
                if (width < calcWidth && height <= calcHeight) {
                    calcWidth = (int) width;
                    calcHeight = (int) height;
                }
            }
        }
        if (calcWidth < 10000000 && calcHeight < 10000000) {
            return new Size(calcWidth, calcHeight);
        }
        // else
        return calculateCameraFrameSize(supportedSizes, accessor, surfaceWidth, surfaceHeight);
    }

    protected boolean initializeCamera(int width, int height) {
        Log.d(TAG, "Initialize java camera");
        boolean result = true;
        synchronized (this) {
            mCamera = null;

            if (mCameraIndex == CAMERA_ID_ANY) {
                Log.d(TAG, "Trying to open camera with old open()");
                try {
                    mCamera = Camera.open();
                    mOpenedCameraIndex = 0;
                }
                catch (Exception e){
                    Log.e(TAG, "Camera is not available (in use or does not exist): " + e.getLocalizedMessage());
                }

                if(mCamera == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    boolean connected = false;
                    for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                        Log.d(TAG, "Trying to open camera with new open(" + Integer.valueOf(camIdx) + ")");
                        try {
                            mCamera = Camera.open(camIdx);
                            mOpenedCameraIndex = camIdx;
                            connected = true;
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Camera #" + camIdx + "failed to open: " + e.getLocalizedMessage());
                        }
                        if (connected) break;
                    }
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    int localCameraIndex = mCameraIndex;
                    if (mCameraIndex == CAMERA_ID_BACK) {
                        Log.i(TAG, "Trying to open back camera");
                        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                        for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                            Camera.getCameraInfo( camIdx, cameraInfo );
                            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                                localCameraIndex = camIdx;
                                break;
                            }
                        }
                    } else if (mCameraIndex == CAMERA_ID_FRONT) {
                        Log.i(TAG, "Trying to open front camera");
                        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                        for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); ++camIdx) {
                            Camera.getCameraInfo( camIdx, cameraInfo );
                            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                                localCameraIndex = camIdx;
                                break;
                            }
                        }
                    }
                    if (localCameraIndex == CAMERA_ID_BACK) {
                        Log.e(TAG, "Back camera not found!");
                    } else if (localCameraIndex == CAMERA_ID_FRONT) {
                        Log.e(TAG, "Front camera not found!");
                    } else {
                        Log.d(TAG, "Trying to open camera with new open(" + Integer.valueOf(localCameraIndex) + ")");
                        try {
                            mCamera = Camera.open(localCameraIndex);
                            mOpenedCameraIndex = localCameraIndex;
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Camera #" + localCameraIndex + "failed to open: " + e.getLocalizedMessage());
                        }
                    }
                }
            }

            if (mCamera == null)
                return false;

            /* Now set camera parameters */
            try {
                Camera.Parameters params = mCamera.getParameters();
                Log.d(TAG, "getSupportedPreviewSizes()");
                List<android.hardware.Camera.Size> sizes = params.getSupportedPreviewSizes();

                if (sizes != null) {
                    /* Select the size that fits surface considering maximum size allowed */
                    Size frameSize = bestCameraFrameSize(sizes, new JavaCameraSizeAccessor(), width, height);

                    /* Image format NV21 causes issues in the Android emulators */
                    if (Build.FINGERPRINT.startsWith("generic")
                            || Build.FINGERPRINT.startsWith("unknown")
                            || Build.MODEL.contains("google_sdk")
                            || Build.MODEL.contains("Emulator")
                            || Build.MODEL.contains("Android SDK built for x86")
                            || Build.MANUFACTURER.contains("Genymotion")
                            || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                            || "google_sdk".equals(Build.PRODUCT))
                        params.setPreviewFormat(ImageFormat.YV12);  // "generic" or "android" = android emulator
                    else
                        params.setPreviewFormat(ImageFormat.NV21);

                    mPreviewFormat = params.getPreviewFormat();

                    Log.d(TAG, "Set preview size to " + Integer.valueOf((int)frameSize.width) + "x" + Integer.valueOf((int)frameSize.height));
                    params.setPreviewSize((int)frameSize.width, (int)frameSize.height);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH && !android.os.Build.MODEL.equals("GT-I9100"))
                        params.setRecordingHint(true);

                    applyBestPreviewFpsRange(params);
                    applyFastExposureControls(params);

                    List<String> FocusModes = params.getSupportedFocusModes();
                    if (FocusModes != null && FocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                    {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    }

                    mCamera.setParameters(params);
                    params = mCamera.getParameters();

                    int previewWidth = params.getPreviewSize().width;
                    int previewHeight = params.getPreviewSize().height;
                    mFrameRotation = getFrameRotation(width, height, previewWidth, previewHeight);
                    boolean swapsDimensions = mFrameRotation == 90 || mFrameRotation == 270;
                    mFrameWidth = swapsDimensions ? previewHeight : previewWidth;
                    mFrameHeight = swapsDimensions ? previewWidth : previewHeight;

                    if ((getLayoutParams().width == LayoutParams.MATCH_PARENT) && (getLayoutParams().height == LayoutParams.MATCH_PARENT))
                        mScale = Math.min(((float)height)/mFrameHeight, ((float)width)/mFrameWidth);
                    else
                        mScale = 0;

                    if (mFpsMeter != null) {
                        mFpsMeter.setResolution(mFrameWidth, mFrameHeight);
                    }

                    int size = previewWidth * previewHeight;
                    size  = size * ImageFormat.getBitsPerPixel(params.getPreviewFormat()) / 8;
                    mBuffer = new byte[size];

                    mCamera.addCallbackBuffer(mBuffer);
                    mCamera.setPreviewCallbackWithBuffer(this);

                    mFrameChain = new Mat[2];
                    mFrameChain[0] = new Mat(previewHeight + (previewHeight/2), previewWidth, CvType.CV_8UC1);
                    mFrameChain[1] = new Mat(previewHeight + (previewHeight/2), previewWidth, CvType.CV_8UC1);

                    AllocateCache();

                    mCameraFrame = new JavaCameraFrame[2];
                    mCameraFrame[0] = new JavaCameraFrame(mFrameChain[0], previewWidth, previewHeight);
                    mCameraFrame[1] = new JavaCameraFrame(mFrameChain[1], previewWidth, previewHeight);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        mSurfaceTexture = new SurfaceTexture(MAGIC_TEXTURE_ID);
                        mCamera.setPreviewTexture(mSurfaceTexture);
                    } else
                       mCamera.setPreviewDisplay(null);

                    /* Finally we are ready to start the preview */
                    Log.d(TAG, "startPreview");
                    mCamera.startPreview();
                }
                else
                    result = false;
            } catch (Exception e) {
                result = false;
                e.printStackTrace();
            }
        }

        return result;
    }

    protected void releaseCamera() {
        synchronized (this) {
            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.setPreviewCallback(null);

                mCamera.release();
            }
            mCamera = null;
            mOpenedCameraIndex = -1;
            if (mFrameChain != null) {
                mFrameChain[0].release();
                mFrameChain[1].release();
            }
            if (mCameraFrame != null) {
                mCameraFrame[0].release();
                mCameraFrame[1].release();
            }
        }
    }

    private boolean mCameraFrameReady = false;

    @Override
    protected boolean connectCamera(int width, int height) {

        /* 1. We need to instantiate camera
         * 2. We need to start thread which will be getting frames
         */
        /* First step - initialize camera connection */
        Log.d(TAG, "Connecting to camera");
        if (!initializeCamera(width, height))
            return false;

        mCameraFrameReady = false;

        /* now we can start update thread */
        Log.d(TAG, "Starting processing thread");
        mStopThread = false;
        mThread = new Thread(new CameraWorker());
        mThread.start();

        return true;
    }

    @Override
    protected void disconnectCamera() {
        /* 1. We need to stop thread which updating the frames
         * 2. Stop camera and release it
         */
        Log.d(TAG, "Disconnecting from camera");
        try {
            mStopThread = true;
            Log.d(TAG, "Notify thread");
            synchronized (this) {
                this.notify();
            }
            Log.d(TAG, "Waiting for thread");
            if (mThread != null)
                mThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            mThread =  null;
        }

        /* Now release camera */
        releaseCamera();

        mCameraFrameReady = false;
    }

    @Override
    public void onPreviewFrame(byte[] frame, Camera arg1) {
        if (BuildConfig.DEBUG)
            Log.d(TAG, "Preview Frame received. Frame size: " + frame.length);
        synchronized (this) {
            mFrameChain[mChainIdx].put(0, 0, frame);
            mCameraFrameReady = true;
            this.notify();
        }
        if (mCamera != null)
            mCamera.addCallbackBuffer(mBuffer);
    }

    private class JavaCameraFrame implements CvCameraViewFrame {
        @Override
        public Mat gray() {
            return mYuvFrameData.submat(0, mHeight, 0, mWidth);
        }

        @Override
        public Mat rgba() {
            if (mPreviewFormat == ImageFormat.NV21)
                Imgproc.cvtColor(mYuvFrameData, mRgba, Imgproc.COLOR_YUV2RGBA_NV21, 4);
            else if (mPreviewFormat == ImageFormat.YV12)
                Imgproc.cvtColor(mYuvFrameData, mRgba, Imgproc.COLOR_YUV2RGB_I420, 4);
            else
                throw new IllegalArgumentException("Preview Format can be NV21 or YV12");

            if (mFrameRotation == 90) {
                Core.rotate(mRgba, mRotatedRgba, Core.ROTATE_90_CLOCKWISE);
                return mRotatedRgba;
            } else if (mFrameRotation == 180) {
                Core.rotate(mRgba, mRotatedRgba, Core.ROTATE_180);
                return mRotatedRgba;
            } else if (mFrameRotation == 270) {
                Core.rotate(mRgba, mRotatedRgba, Core.ROTATE_90_COUNTERCLOCKWISE);
                return mRotatedRgba;
            }
            return mRgba;
        }

        public JavaCameraFrame(Mat Yuv420sp, int width, int height) {
            super();
            mWidth = width;
            mHeight = height;
            mYuvFrameData = Yuv420sp;
            mRgba = new Mat();
            mRotatedRgba = new Mat();
        }

        public void release() {
            mRgba.release();
            mRotatedRgba.release();
        }

        private Mat mYuvFrameData;
        private Mat mRgba;
        private Mat mRotatedRgba;
        private int mWidth;
        private int mHeight;
    };

    private int getFrameRotation(int surfaceWidth, int surfaceHeight, int previewWidth, int previewHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD || mOpenedCameraIndex < 0) {
            return surfaceHeight > surfaceWidth && previewWidth > previewHeight ? 90 : 0;
        }

        Camera.CameraInfo info = new Camera.CameraInfo();
        try {
            Camera.getCameraInfo(mOpenedCameraIndex, info);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to read camera info: " + e.getLocalizedMessage());
            return surfaceHeight > surfaceWidth && previewWidth > previewHeight ? 90 : 0;
        }

        int displayDegrees = 0;
        WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            switch (windowManager.getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90:
                    displayDegrees = 90;
                    break;
                case Surface.ROTATION_180:
                    displayDegrees = 180;
                    break;
                case Surface.ROTATION_270:
                    displayDegrees = 270;
                    break;
                case Surface.ROTATION_0:
                default:
                    displayDegrees = 0;
                    break;
            }
        }

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - ((info.orientation + displayDegrees) % 360)) % 360;
        }
        return (info.orientation - displayDegrees + 360) % 360;
    }

    private class CameraWorker implements Runnable {

        @Override
        public void run() {
            do {
                boolean hasFrame = false;
                synchronized (OpencvCameraView.this) {
                    try {
                        while (!mCameraFrameReady && !mStopThread) {
                            OpencvCameraView.this.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (mCameraFrameReady)
                    {
                        mChainIdx = 1 - mChainIdx;
                        mCameraFrameReady = false;
                        hasFrame = true;
                    }
                }

                if (!mStopThread && hasFrame) {
                    if (!mFrameChain[1 - mChainIdx].empty())
                        deliverAndDrawFrame(mCameraFrame[1 - mChainIdx]);
                }
            } while (!mStopThread);
            Log.d(TAG, "Finish processing thread");
        }
    }
}

