package com.example.chapter07.get_click_and_picture;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.chapter07.R;

public class GetClickAndPictureActivity extends AppCompatActivity {

    private static final String TAG = "GetClickAndPicture";
    private static final int REQUEST_MEDIA_PROJECTION = 3001;

    private EditText etFolderName;
    private TextView tvStatus;
    private TextView tvPath;
    private Button btnRequestPermission;
    private Button btnStart;
    private Button btnStop;

    private MediaProjectionManager projectionManager;
    private ClickAndPictureEventController controller;
    private String pendingFolderName = "click_picture_session";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_get_click_and_picture);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Log.d(TAG, "onCreate: projectionManager=" + projectionManager
                + ", sdk=" + android.os.Build.VERSION.SDK_INT
                + ", package=" + getPackageName());

        initViews();
        setupListeners();
        updateRunningState(false);
    }

    private void initViews() {
        etFolderName = findViewById(R.id.et_click_picture_folder_name);
        tvStatus = findViewById(R.id.tv_click_picture_status);
        tvPath = findViewById(R.id.tv_click_picture_path);
        btnRequestPermission = findViewById(R.id.btn_click_picture_permission);
        btnStart = findViewById(R.id.btn_click_picture_start);
        btnStop = findViewById(R.id.btn_click_picture_stop);

        tvStatus.setText("状态：未启动");
        tvPath.setText("保存位置：/data/user/0/com.example.chapter07/databases/[自定义文件夹名]/");
        etFolderName.setText(pendingFolderName);
    }

    private void setupListeners() {
        btnRequestPermission.setOnClickListener(v -> requestProjectionPermission());

        btnStart.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.setText("状态：缺少悬浮窗权限");
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                Intent overlayIntent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(overlayIntent);
                return;
            }
            requestProjectionPermission();
        });

        btnStop.setOnClickListener(v -> stopController());
    }

    private void requestProjectionPermission() {
        pendingFolderName = etFolderName.getText().toString().trim();
        if (TextUtils.isEmpty(pendingFolderName)) {
            pendingFolderName = "click_picture_session";
            etFolderName.setText(pendingFolderName);
        }

        Log.d(TAG, "requestProjectionPermission: folder=" + pendingFolderName
                + ", canDrawOverlays=" + Settings.canDrawOverlays(this)
                + ", projectionManager=" + projectionManager);

        if (projectionManager == null) {
            tvStatus.setText("状态：设备不支持截屏服务");
            Toast.makeText(this, "MediaProjectionManager 不可用", Toast.LENGTH_LONG).show();
            return;
        }

        tvStatus.setText("状态：等待截屏授权结果");
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        Log.d(TAG, "requestProjectionPermission: launch capture intent=" + captureIntent);
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode
                + ", resultCode=" + resultCode
                + ", hasData=" + (data != null)
                + ", overlay=" + Settings.canDrawOverlays(this));
        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            return;
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            tvStatus.setText("状态：截屏授权被取消或失败");
            Toast.makeText(this, "未获得截屏授权", Toast.LENGTH_SHORT).show();
            return;
        }

        tvStatus.setText("状态：截屏授权成功，正在初始化");
        startController(resultCode, data);
    }

    private void startController(int resultCode, Intent data) {
        Log.d(TAG, "startController: begin, resultCode=" + resultCode
                + ", hasData=" + (data != null)
                + ", overlay=" + Settings.canDrawOverlays(this)
                + ", folder=" + pendingFolderName);
        stopController();
        startMediaProjectionForegroundService();

        try {
            Log.d(TAG, "startController: creating ClickAndPictureEventController");
            controller = new ClickAndPictureEventController(
                    this,
                    projectionManager,
                    resultCode,
                    data,
                    pendingFolderName,
                    new ClickAndPictureEventController.EventListener() {
                        @Override
                        public void onEventStored(ClickPictureEventStore.ClickPictureRecord record) {
                            runOnUiThread(() -> {
                                tvStatus.setText("状态：运行中，最近记录 ID=" + record.id +
                                        " 坐标=(" + record.clickX + ", " + record.clickY + ")");
                                tvPath.setText("保存位置：" + record.imagePath + "\n数据库："
                                        + controller.getEventStore().getDatabaseFile().getAbsolutePath());
                            });
                        }

                        @Override
                        public void onError(String message, Throwable throwable) {
                            Log.e(TAG, message, throwable);
                            runOnUiThread(() -> {
                                tvStatus.setText("状态：" + message);
                                Toast.makeText(
                                        GetClickAndPictureActivity.this,
                                        message + "：" + safeErrorMessage(throwable),
                                        Toast.LENGTH_LONG
                                ).show();
                            });
                        }

                        @Override
                        public void onPassThroughStateChanged(boolean passThroughEnabled) {
                            runOnUiThread(() -> {
                                if (passThroughEnabled) {
                                    tvStatus.setText("状态：已记录点击，0.5 秒放行中");
                                }
                            });
                        }
                    }
            );
            Log.d(TAG, "startController: controller created, calling start()");
            controller.start();
            Log.d(TAG, "startController: controller.start() success");
            updateRunningState(true);
            tvStatus.setText("状态：运行中，等待屏幕点击");
            tvPath.setText("保存目录：" + controller.getEventStore().getSessionDir().getAbsolutePath());
        } catch (Throwable throwable) {
            Log.e(TAG, "启动点击记录事件失败", throwable);
            controller = null;
            stopMediaProjectionForegroundService();
            String message = "启动失败：" + safeErrorMessage(throwable);
            tvStatus.setText("状态：" + message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            updateRunningState(false);
        }
    }

    private void stopController() {
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        stopMediaProjectionForegroundService();
        updateRunningState(false);
        tvStatus.setText("状态：未启动");
    }

    private void startMediaProjectionForegroundService() {
        Intent serviceIntent = new Intent(this, MediaProjectionForegroundService.class);
        serviceIntent.setAction(MediaProjectionForegroundService.ACTION_START);
        Log.d(TAG, "startMediaProjectionForegroundService: action=" + serviceIntent.getAction());
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void stopMediaProjectionForegroundService() {
        Intent serviceIntent = new Intent(this, MediaProjectionForegroundService.class);
        serviceIntent.setAction(MediaProjectionForegroundService.ACTION_STOP);
        Log.d(TAG, "stopMediaProjectionForegroundService: action=" + serviceIntent.getAction());
        startService(serviceIntent);
    }

    private void updateRunningState(boolean running) {
        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        etFolderName.setEnabled(!running);
    }

    private String safeErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        if (TextUtils.isEmpty(message)) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    @Override
    protected void onDestroy() {
        stopController();
        super.onDestroy();
    }
}
