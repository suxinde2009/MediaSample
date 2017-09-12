package com.ymlion.mediasample.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings("deprecation") public class CameraCapture
        implements Camera.FaceDetectionListener {
    private static final String TAG = CameraCapture.class.getSimpleName();
    public static final int FLASH_MODE_OFF = 0;
    public static final int FLASH_MODE_ON = 1;
    public static final int FLASH_MODE_AUTO = 2;
    public static final int FRONT = 1;
    public static final int BACK = 2;

    private Size mPictureSize;
    private Size mPreviewSize;

    private int mFlashMode = FLASH_MODE_AUTO;
    private boolean mIsAutoFocusSupport;

    private int mCameraFacing = CameraInfo.CAMERA_FACING_BACK;
    private Camera mCamera;

    private ICameraCallback mCallback;

    private WeakReference<Context> mContext;
    private SurfaceHolder mSurface;
    private Paint mPaint;
    private SurfaceHolder mFaceSurface;

    public CameraCapture(Context context) {
        mContext = new WeakReference<Context>(context);
    }

    public void setCameraType(int cameraType) {
        mCameraFacing = getCameraFacing(cameraType);
    }

    public void changeFacing() {
        if (mCameraFacing == getCameraFacing(FRONT)) {
            setCameraType(BACK);
        } else {
            setCameraType(FRONT);
        }
    }

    public void setCameraCallback(ICameraCallback callback) {
        mCallback = callback;
    }

    public void open(SurfaceHolder holder, int wantedMinPreviewWidth) {
        if (null == mContext.get()) {
            return;
        }

        if (null != mCamera) {
            close();
        }

        try {
            mSurface = holder;
            mCamera = Camera.open(mCameraFacing);
            Parameters parameters = mCamera.getParameters();
            mPictureSize = Collections.max(parameters.getSupportedPictureSizes(),
                    new CompareSizesByArea());
            parameters.setPictureSize(mPictureSize.width, mPictureSize.height);
            Log.i(TAG, "picture size: " + mPictureSize.width + "*" + mPictureSize.height);
            mPreviewSize =
                    chooseOptimalSize(parameters.getSupportedPreviewSizes(), wantedMinPreviewWidth,
                            mPictureSize);
            parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
            Log.i(TAG, "preview size: " + mPreviewSize.width + "*" + mPreviewSize.height);
            if (null != mCallback) {
                mCallback.onInitFinished(mPreviewSize.width, mPreviewSize.height);
            }
            setupFlashMode(parameters);
            setupFocusMode(parameters);
            //parameters.setPreviewFormat(ImageFormat.NV21);
            mCamera.setParameters(parameters);
            int orientation = determineDisplayOrientation();
            Log.i(TAG, "orientation: " + orientation);
            if (orientation > 0) {
                mCamera.setDisplayOrientation(orientation);
            }
            mCamera.setPreviewDisplay(mSurface);
            /*mCamera.setPreviewTexture(new SurfaceTexture(10));
            mCamera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override public void onPreviewFrame(byte[] data, Camera camera) {
                    Log.d(TAG, "onPreviewFrame: " + data.length);
                    Size size = camera.getParameters().getPreviewSize();
                    //这里一定要得到系统兼容的大小，否则解析出来的是一片绿色或者其他
                    YuvImage yuvImage =
                            new YuvImage(data, ImageFormat.NV21, size.width, size.height, null);
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    yuvImage.compressToJpeg(new Rect(0, 0, size.width, size.height), 80,
                            outputStream);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.RGB_565;//必须设置为565，否则无法检测
                    byte[] bytes = outputStream.toByteArray();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    Matrix matrix = new Matrix();
                    matrix.postRotate(determineDisplayOrientation());
                    //matrix.postScale(0.25f, 0.25f);
                    Bitmap bm =
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                                    matrix, true);
                    Canvas canvas = mSurface.lockCanvas(null);
                    canvas.drawBitmap(bm, 0, 0, new Paint());
                    mSurface.unlockCanvasAndPost(canvas);
                }
            });*/
            mCamera.startPreview();
            if (mCamera.getParameters().getMaxNumDetectedFaces() > 0) {
                mCamera.setFaceDetectionListener(this);
                mCamera.startFaceDetection();
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to open camera: " + mCameraFacing);
        }
    }

    public void close() {
        if (null != mCamera) {
            if (mCamera.getParameters().getMaxNumDetectedFaces() > 0) {
                mCamera.stopFaceDetection();
            }
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    public void setFlashMode(int flashMode) {
        if (mFlashMode == flashMode) {
            return;
        }
        mFlashMode = flashMode;
        if (null != mCamera) {
            Parameters parameters = mCamera.getParameters();
            setupFlashMode(parameters);
            mCamera.setParameters(parameters);
        }
    }

    public void takePicture() {
        Log.i(TAG, "takePicture()");
        if (null == mCamera) {
            return;
        }

        if (mIsAutoFocusSupport) {
            mCamera.autoFocus(mAutoFocusCallback);
        } else {
            mCamera.takePicture(null, null, mPictureCallback);
        }
    }

    private int getCameraFacing(int cameraType) {
        return (FRONT == cameraType) ? CameraInfo.CAMERA_FACING_FRONT
                : CameraInfo.CAMERA_FACING_BACK;
    }

    private Size chooseOptimalSize(List<Size> choices, int wantedMinWidth, Size aspectRatio) {
        List<Size> results = new ArrayList<Size>();
        for (Size choice : choices) {
            if (choice.width * aspectRatio.height == choice.height * aspectRatio.width
                    && choice.height >= wantedMinWidth) {
                results.add(choice);
            }
        }

        if (results.size() > 0) {
            return Collections.min(results, new CompareSizesByArea());
        } else {
            Log.e(TAG, "failed to any suitable preview size");
            return choices.get(0);
        }
    }

    private void setupFlashMode(Parameters parameters) {
        List<String> flashModes = parameters.getSupportedFlashModes();
        if (null == flashModes) {
            return;
        }

        String flashMode = Parameters.FLASH_MODE_AUTO;
        if (FLASH_MODE_OFF == mFlashMode) {
            flashMode = Parameters.FLASH_MODE_OFF;
        } else if (FLASH_MODE_ON == mFlashMode) {
            flashMode = Parameters.FLASH_MODE_ON;
        }
        parameters.setFlashMode(flashMode);
    }

    private void setupFocusMode(Parameters parameters) {
        mIsAutoFocusSupport = false;
        List<String> choices = parameters.getSupportedFocusModes();
        if (choices.contains(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            mIsAutoFocusSupport = true;
            parameters.setFocusMode(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        } else if (choices.contains(Parameters.FOCUS_MODE_AUTO)) {
            mIsAutoFocusSupport = true;
            parameters.setFocusMode(Parameters.FLASH_MODE_AUTO);
        } else if (choices.contains(Parameters.FOCUS_MODE_MACRO)) {
            mIsAutoFocusSupport = true;
            parameters.setFocusMode(Parameters.FOCUS_MODE_MACRO);
        }
        Log.i(TAG, "auto focus: " + mIsAutoFocusSupport);
    }

    private int determineDisplayOrientation() {
        Context context = mContext.get();
        if (context == null) {
            Log.e(TAG, "context has been destroyed");
            return 0;
        }

        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(mCameraFacing, cameraInfo);

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = wm.getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;
        if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
            result = (cameraInfo.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (cameraInfo.orientation - degrees + 360) % 360;
        }

        return result;
    }

    private final PictureCallback mPictureCallback = new PictureCallback() {
        @Override public void onPictureTaken(byte[] data, Camera camera) {
            Log.i(TAG, "onPictureTaken()");
            camera.stopPreview();
            if (null != mCallback) {
                mCallback.onImageAvailable(data);
            }
            camera.cancelAutoFocus();
            mCamera.startPreview();
        }
    };

    private final AutoFocusCallback mAutoFocusCallback = new AutoFocusCallback() {
        @Override public void onAutoFocus(boolean success, Camera camera) {
            Log.i(TAG, "onAutoFocus(): " + success);
            camera.takePicture(null, null, mPictureCallback);
        }
    };

    @Override public void onFaceDetection(Camera.Face[] faces, Camera camera) {
        if (faces.length < 1) {
            return;
        }
        Canvas canvas = mFaceSurface.lockCanvas();//锁定Surface 并拿到Canvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);// 清除上一次绘制
        Matrix matrix = new Matrix();

        //        这里使用的是后置摄像头就不用翻转。由于没有进行旋转角度的兼容，这里直接传系统调整的值
        prepareMatrix(matrix, mCameraFacing == getCameraFacing(FRONT),
                determineDisplayOrientation(), 1440, 2478);

        //        canvas.save();
        //        由于有的时候手机会存在一定的偏移（歪着拿手机）所以在这里需要旋转Canvas 和 matrix，
        //        偏移值从OrientationEventListener获得，具体Google
        //        canvas.rotate(-degrees); 默认是逆时针旋转
        //        matrix.postRotate(degrees);默认是顺时针旋转
        for (int i = 0; i < faces.length; i++) {
            RectF rect = new RectF(faces[i].rect);
            Log.d(TAG, "onFaceDetection: " + rect);
            matrix.mapRect(rect);//应用到rect上
            Log.i(TAG, "onFaceDetection: " + rect);
            canvas.drawRect(rect, mPaint);
        }
        mFaceSurface.unlockCanvasAndPost(canvas);//更新Canvas并解锁
    }

    /**
     * 该方法出自
     * http://blog.csdn.net/yanzi1225627/article/details/38098729/
     * http://bytefish.de/blog/face_detection_with_android/
     *
     * @param matrix 这个就不用说了
     * @param mirror 是否需要翻转，后置摄像头（手机背面）不需要翻转，前置摄像头需要翻转。
     * @param displayOrientation 旋转的角度
     * @param viewWidth 预览View的宽高
     */
    public void prepareMatrix(Matrix matrix, boolean mirror, int displayOrientation, int viewWidth,
            int viewHeight) {
        // Need mirror for front camera.
        matrix.setScale(mirror ? -1 : 1, 1);
        // This is the value for android.hardware.Camera.setDisplayOrientation.
        matrix.postRotate(displayOrientation);
        // Camera driver coordinates range from (-1000, -1000) to (1000, 1000).
        // UI coordinates range from (0, 0) to (width, height)
        matrix.postScale(viewWidth / 2000f, viewHeight / 2000f);
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f);
    }

    public void setFaceHolder(SurfaceHolder holder) {
        mFaceSurface = holder;
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setColor(Color.RED);
        mPaint.setStrokeWidth(5);
        mPaint.setStyle(Paint.Style.STROKE);
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.width * lhs.height - (long) rhs.width * rhs.height);
        }
    }
}