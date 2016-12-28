package de.weis.multisensor_grabber;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.util.Xml;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Text;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF;
import static android.hardware.camera2.CaptureResult.CONTROL_AE_MODE;
import static android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME;

public class MainActivity extends Activity {
    private static final String TAG = "Multisensor_Grabber";
    private ImageButton takePictureButton;
    private TextureView textureView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private String cameraId;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest captureRequest;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private ImageReader imageReader;
    private File file;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private boolean mFlashSupported;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    File _dir;
    FileOutputStream fileos;
    XmlSerializer serializer = Xml.newSerializer();

    CameraManager manager;
    CameraCharacteristics characteristics;
    ImageReader reader;
    CameraCaptureSession _session;
    CaptureRequest.Builder _capReq; // needs to be global b/c preview-setup

    Long last_pic_ts = new Long(0);

    protected Integer _cnt = 0;
    protected Boolean _recording = false;
    Integer _img_width = 640;
    Integer _img_height = 480;
    boolean _fix_exp;
    Long _exp_time;
    Float _fps;
    Long _diff = new Long(0);

    Long _seq_timestamp;

    double _gyro_head = 0.;
    double _gyro_pitch = 0.;
    double _gyro_roll = 0.;

    double _accel_x = 0.;
    double _accel_y = 0.;
    double _accel_z = 0.;

    private Handler sys_handler = new Handler();
    private TextView textview_battery;
    LocationManager mLocationManager;
    Criteria criteria = new Criteria();
    String bestProvider;
    android.location.Location _loc;
    TextView textview_coords;
    TextView textview_fps;
    TextView textview_imu;

    private Runnable grab_system_data = new Runnable() {
        @Override
        public void run() {
            textview_battery.setText("BAT: " + String.format("%.0f", getBatteryLevel()) + "%");
            // FIXME: if we have gps-permission, but gps is off, this fails!
            try {
                textview_coords.setText("Coordinates: " + String.format("%.03f", _loc.getLatitude()) + ", " + String.format("%.03f", _loc.getLongitude()) + ", Acc:" + _loc.getAccuracy());
            }catch(Exception e){
                Log.e("GPS", "---------------- NO GPS DATA");
            };
            textview_imu.setText("head: " + String.format("%.01f", _gyro_head) +
                    " pitch: " + String.format("%.01f", _gyro_pitch) +
                    " roll: " + String.format("%.01f", _gyro_roll) +
                    " ax " + String.format("%.01f", _accel_x) +
                    " ay " + String.format("%.01f", _accel_y) +
                    " az " + String.format("%.01f", _accel_z));
            textview_fps.setText(String.format("%.1f", 1000. / _diff) + " f/s");
            sys_handler.postDelayed(grab_system_data, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textview_battery = (TextView) findViewById(R.id.textview_battery);
        textview_coords = (TextView) findViewById(R.id.textview_coords);
        textview_imu = (TextView) findViewById(R.id.textview_imu);
        textview_fps = (TextView) findViewById(R.id.textview_fps);

        textureView = (TextureView) findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);

        takePictureButton = (ImageButton) findViewById(R.id.btn_takepicture);
        assert takePictureButton != null;
        takePictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (_recording) {
                    try {
                        _session.stopRepeating();
                        _session.close();
                        createCameraPreview();
                        //mBackgroundHandler.removeCallbacksAndMessages(null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    _recording = false;
                    try {
                        serializer.endTag(null, "sequence");
                        serializer.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    try {
                        fileos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    takePictureButton.setImageResource(R.mipmap.icon_rec);
                    Toast.makeText(getApplicationContext(), "Stopped", Toast.LENGTH_LONG).show();
                    /*
                    _pichandler.removeCallbacks(_picrunner);
                    */
                } else {
                    Toast.makeText(getApplicationContext(), "Started", Toast.LENGTH_LONG).show();
                    takePictureButton.setImageResource(R.mipmap.icon_rec_on);
                    _recording = true;
                    takePicture();
                    /*_pichandler.postDelayed(_picrunner, 1000);*/
                }
            }
        });

        final ImageButton settingsButton = (ImageButton) findViewById(R.id.btn_settings);
        settingsButton.setOnClickListener(
                new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                        // on android 6.0, camera needs to be closed before starting this new intent

                        startActivity(intent);
                    }
                }
        );


        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.e(TAG, "onOpened");
            cameraDevice = camera;
            try {
                characteristics = manager.getCameraCharacteristics(cameraDevice.getId());

                // read preferences
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                // FIXME: if exposure-time is e.g. 30ms, we get pictures at:
                // 30, 60, 90, 120 and will always select 120 if we chose 10fps.
                // better method would be to
                // a) if auto-mode: estimate exposure time, provide framerate-chooser
                // b) if manual mode: add some time for processing, provide choice for every frame, every 2nd frame, etc.
                _fps = Float.parseFloat(prefs.getString("pref_framerates", "10."));

                // ------------ resolution and setup reader and output surfaces
                String selected_res = prefs.getString("pref_resolutions", ""); // this gives the value
                if (selected_res != "") {
                    //Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
                    // FIXME: max framerate with JPEG is 150ms == ca. 6.6 fps on GS5
                    // GS6: 36-40ms
                    Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);

                    _img_width = sizes[Integer.parseInt(selected_res)].getWidth(); //  .get(Integer.parseInt(selected_res)).width;
                    _img_height = sizes[Integer.parseInt(selected_res)].getHeight();
                }

                reader = ImageReader.newInstance(_img_width, _img_height, ImageFormat.JPEG, 2);
                //reader = ImageReader.newInstance(_img_width, _img_height, ImageFormat.YUV_420_888, 2); // YUV is way faster

                /*
                outputSurfaces = new ArrayList<Surface>(2);
                outputSurfaces.add(reader.getSurface());
                outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
                */

            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            createCameraPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    final CameraCaptureSession.CaptureCallback previewCallbackListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            _diff = System.currentTimeMillis() - last_pic_ts;
            last_pic_ts = System.currentTimeMillis();
        }
    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }

        _seq_timestamp = System.currentTimeMillis();
        // this is SD-storage, android/data/de.weis.multisensor_grabber/files/
        try {
            _dir = new File(getExternalFilesDirs(null)[1], "" + _seq_timestamp);
        } catch (Exception e) {
            _dir = new File(getExternalFilesDirs(null)[0], "" + _seq_timestamp);
        }
        _dir.mkdirs();

        try {
            fileos = new FileOutputStream(new File(_dir.getPath() + File.separator + _seq_timestamp + ".xml"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        try {
            serializer.setOutput(fileos, "UTF-8");
            serializer.startDocument(null, Boolean.valueOf(true));
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startTag(null, "sequence");
            serializer.attribute(null, "folder", _dir.getAbsolutePath() + File.separator);
            String manufacturer = Build.MANUFACTURER;
            String model = Build.MODEL;
            serializer.attribute(null, "sensor", manufacturer + model);
            serializer.attribute(null, "ts", "" + _seq_timestamp);
            //serializer.attribute(null, "whitebalance", mCamera.getParameters().get("whitebalance").toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(_img_width, _img_height);
            configureTransform(textureView.getWidth(), textureView.getHeight());

            Surface surface = new Surface(texture);

            //captureRequestBuilder.addTarget(reader.getSurface());
            List<android.view.Surface> surfaces = new ArrayList<Surface>(2);
            surfaces.add(surface);
            surfaces.add(reader.getSurface());
            _capReq = get_captureBuilder(surfaces);

            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader myreader) {
                    Image image = null;
                    try {
                        image = myreader.acquireLatestImage();
                        if(image == null){
                            return;
                        }

                        Log.d("_diff", "-------------------------------- " + (System.currentTimeMillis() - last_pic_ts));
                        if ((System.currentTimeMillis() - last_pic_ts) >= 1000. / _fps) {
                            Log.d("1000/fps", "--------------------------------================================== " + 1000. / _fps);
                            _diff = System.currentTimeMillis() - last_pic_ts;
                            last_pic_ts = System.currentTimeMillis();

                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.capacity()];
                            buffer.get(bytes);
                            save(bytes);
                        }
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }

                private void save(byte[] bytes) throws IOException {
                    OutputStream output = null;
                    try {
                        String fname = "pic" + _cnt + ".jpg";
                        File file = new File(_dir.getPath() + File.separator + fname);
                        output = new FileOutputStream(file);
                        output.write(bytes);

                        try {
                            serializer.startTag(null, "Frame");
                            serializer.attribute(null, "uri", fname);
                            serializer.attribute(null, "lat", "" + _loc.getLatitude());
                            serializer.attribute(null, "lon", "" + _loc.getLongitude());
                            serializer.attribute(null, "acc", "" + _loc.getAccuracy());
                            serializer.attribute(null, "img_w", "" + _img_width);
                            serializer.attribute(null, "img_h", "" + _img_height);
                            serializer.attribute(null, "speed", "" + _loc.getSpeed());
                            serializer.attribute(null, "ts_cam", "" + last_pic_ts);
                            if (_fix_exp) {
                                serializer.attribute(null, "exp_time", "" + _exp_time);
                            } else {
                                //FIXME: is it possible to get the exposure time of each single image if auto-exposure is on?
                                serializer.attribute(null, "exp_time", "-1");
                            }

                            serializer.attribute(null, "avelx", ""+_gyro_roll);
                            serializer.attribute(null, "avely", ""+_gyro_head);
                            serializer.attribute(null, "avelz", ""+_gyro_pitch);
                            serializer.attribute(null, "accx", ""+_accel_x);
                            serializer.attribute(null, "accy", ""+_accel_y);
                            serializer.attribute(null, "accz", ""+_accel_z);

                            serializer.endTag(null, "Frame");
                            serializer.flush();

                        } catch (IOException e) {
                            Toast.makeText(MainActivity.this, "Serializer IOExcept: " + e, Toast.LENGTH_LONG);
                            //Log.e("serializer", "IOException: " + e);
                        }
                        // show inter-frame framerate (actual)
                        _cnt += 1;
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }
            };

            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    // does not work on GS5
                    /*
                    Log.e(TAG, "Available keys = " + result.getKeys().toString());
                    Log.e(TAG, "Exposure time = " + result.get(CaptureResult.SENSOR_EXPOSURE_TIME));
			        Log.e(TAG, "Frame duration = " + result.get(CaptureResult.SENSOR_FRAME_DURATION));
                    Log.e(TAG, "Sensor sensitivity = " + result.get(CaptureResult.SENSOR_SENSITIVITY));
                    */
                    //Toast.makeText(getApplicationContext(), "Saved:" + _cnt, Toast.LENGTH_SHORT).show();
                }
            };

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        _session = session;
                        // use the same captureRequest builder as for the preview,
                        // this has already been built from user preferences!
                        session.setRepeatingRequest(_capReq.build(), captureListener, mBackgroundHandler);
                        //session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    CaptureRequest.Builder get_captureBuilder(List<android.view.Surface> outputsurfaces){
        CaptureRequest.Builder captureBuilder = null;
        try {
            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        for(Surface s:outputsurfaces){
            captureBuilder.addTarget(s);
        }

        captureBuilder.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);

        // http://stackoverflow.com/questions/29265126/android-camera2-capture-burst-is-too-slow
        // FIXME: expose to settings?
        captureBuilder.set(CaptureRequest.CONTROL_AWB_LOCK,true);
        captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);

        captureBuilder.set(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_OFF);
        captureBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
        captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);

        /* user prefs */
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
        _fix_exp=prefs.getBoolean("pref_fix_exp",false);
        _exp_time=Long.parseLong(prefs.getString("pref_exposure","0")) * 1000000;
        boolean fix_foc = prefs.getBoolean("pref_fix_foc", false);

        if(fix_foc) {
            Float foc_dist = Float.parseFloat(prefs.getString("pref_focus_dist", "0"));
            captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, foc_dist);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CONTROL_AF_MODE_OFF);
            Log.d("Focus", "------------------------------- set focus dist to: " + foc_dist);
        }

        if(_fix_exp){
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CONTROL_AE_MODE_OFF);
            captureBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
        }

        if(_exp_time!=0){
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, _exp_time);
        }

        // Orientation
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,ORIENTATIONS.get(rotation));

        return captureBuilder;
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(_img_width, _img_height);

            Log.d("textureView", "========================= calling configureTransform");
            configureTransform(textureView.getWidth(),textureView.getHeight());

            // for the preview, we only want the preview-surface as output
            Surface surface = new Surface(texture);
            List<android.view.Surface> surfaces = new ArrayList<Surface>(1);
            surfaces.add(surface);

            _capReq = get_captureBuilder(surfaces);

            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void openCamera() {
        Log.e(TAG, "is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;

            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            manager.openCamera(cameraId, stateCallback, null);
            mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            bestProvider = mLocationManager.getBestProvider(criteria, false);
            _loc = mLocationManager.getLastKnownLocation(bestProvider);
            mLocationManager.requestLocationUpdates(bestProvider, 1,1, locationListener);

            SensorManager sm = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
            sm.registerListener(sel,
                    sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                    SensorManager.SENSOR_DELAY_UI);
            sm.registerListener(sel,
                    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_UI);

            sys_handler.postDelayed(grab_system_data, 1);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }

    protected void updatePreview() {
        if(null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }

        try {
            cameraCaptureSessions.setRepeatingRequest(_capReq.build(), previewCallbackListener, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView){
            Log.d("configTrans", "------------------------------textureView is null!");
            return;
        }

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        Log.d("imageDim", "=============================== height: " + _img_height + ", width: " + _img_width);
        RectF bufferRect = new RectF(0, 0, _img_height, _img_width);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / _img_height,
                    (float) viewWidth / _img_width);
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

    final LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {}
        public void onProviderDisabled(String provider){}
        public void onProviderEnabled(String provider){ }
        public void onStatusChanged(String provider, int status, Bundle extras){
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();

        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }
    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        _recording = false;
        takePictureButton.setImageResource(R.mipmap.icon_rec);
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    public float getBatteryLevel() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        // Error checking that probably isn't needed but I added just in case.
        if(level == -1 || scale == -1) {
            return 50.0f;
        }

        return ((float)level / (float)scale) * 100.0f;
    }

    private final SensorEventListener sel = new SensorEventListener() {
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                updateOrientation(event.values[0], event.values[1], event.values[2]);
            }
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                updateAccels(event.values[0], event.values[1], event.values[2]);
            }
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    // FIXME: are semaphores needed here?
    private void updateOrientation(float heading, float pitch, float roll) {
        _gyro_head = heading;
        _gyro_pitch = pitch;
        _gyro_roll = roll;
    }

    private void updateAccels(float x, float y, float z){
        _accel_x = x;
        _accel_y = y;
        _accel_z = z;
    }
}