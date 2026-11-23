package com.example.chapter07.thirdlevel;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.chapter07.EdgeToEdgeInsets;
import com.example.chapter07.R;

/**
 * 第三级独立入口：配置服务、申请权限并启动屏幕文字风险检测。
 */
public class ThirdLevelActivity extends AppCompatActivity {

    private static final String PREFS = "third_level_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_API_TOKEN = "api_token";
    private static final String KEY_ENABLE_AUDIO = "enable_audio";
    private static final int REQUEST_PROJECTION = 3101;
    private static final int REQUEST_NOTIFICATION = 3102;
    private static final int REQUEST_RECORD_AUDIO = 3103;

    private EditText serverUrlInput;
    private EditText tokenInput;
    private CheckBox audioCaptureCheckBox;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private boolean startAfterNotification;
    private boolean startAfterAudioPermission;

    private final ScreenRiskService.StatusListener statusListener =
            (running, title, detail) -> runOnUiThread(() -> {
                statusView.setText(title + (TextUtils.isEmpty(detail) ? "" : "\n" + detail));
                updateRunningState(running);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_third_level);
        EdgeToEdgeInsets.apply(this, findViewById(R.id.thirdLevelRoot));
        // 检测页面保持屏幕常亮：避免灭屏后 MediaProjection 只能采到黑屏，
        // 演示/验收过程中被误显示为“检测受限”。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        serverUrlInput = findViewById(R.id.riskServerUrlInput);
        tokenInput = findViewById(R.id.riskApiTokenInput);
        audioCaptureCheckBox = findViewById(R.id.thirdLevelAudioCheckBox);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        serverUrlInput.setText(loadServerUrl(this));
        tokenInput.setText(loadApiToken(this));
        audioCaptureCheckBox.setChecked(preferences(this).getBoolean(KEY_ENABLE_AUDIO, false));

        statusView = findViewById(R.id.thirdLevelStatus);
        startButton = findViewById(R.id.thirdLevelStartButton);
        stopButton = findViewById(R.id.thirdLevelStopButton);
        Button overlayButton = findViewById(R.id.thirdLevelOverlayPermissionButton);
        Button demoButton = findViewById(R.id.thirdLevelDemoButton);

        overlayButton.setOnClickListener(v -> openOverlayPermission());
        // 演示画面入口：只跳转内置测试画面页，不影响自动检测的开始/停止流程。
        demoButton.setOnClickListener(v ->
                startActivity(new Intent(ThirdLevelActivity.this, ThirdLevelDemoActivity.class)));
        startButton.setOnClickListener(v -> requestStart());
        stopButton.setOnClickListener(v -> stopDetection());
        updateRunningState(ScreenRiskService.isRunning());
    }

    @Override
    protected void onStart() {
        super.onStart();
        ScreenRiskService.setStatusListener(statusListener);
        ScreenRiskService.StatusSnapshot snapshot = ScreenRiskService.getLastStatus();
        String currentText = statusView.getText().toString();
        if (snapshot.running || currentText.isEmpty()
                || getString(R.string.third_level_not_started).equals(currentText)) {
            statusView.setText(snapshot.title
                    + (TextUtils.isEmpty(snapshot.detail) ? "" : "\n" + snapshot.detail));
        }
        updateRunningState(snapshot.running);
    }

    @Override
    protected void onStop() {
        ScreenRiskService.setStatusListener(null);
        super.onStop();
    }

    private void requestStart() {
        String serverUrl = normalizeServerUrl(serverUrlInput.getText().toString());
        String token = tokenInput.getText().toString().trim();
        if (TextUtils.isEmpty(serverUrl) || !serverUrl.startsWith("http")) {
            Toast.makeText(this, "请填写完整的风险服务地址", Toast.LENGTH_LONG).show();
            return;
        }
        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, "请填写风险服务测试令牌", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            openOverlayPermission();
            return;
        }

        saveSettings(this, serverUrl, token, audioCaptureCheckBox.isChecked());
        startButton.setEnabled(false);
        statusView.setText("正在检查风险分析服务…");
        new RiskApiClient(serverUrl, token).checkHealth(new RiskApiClient.HealthCallback() {
            @Override
            public void onHealthy() {
                runOnUiThread(ThirdLevelActivity.this::requestRuntimePermissions);
            }

            @Override
            public void onUnavailable(String message) {
                runOnUiThread(() -> {
                    statusView.setText("分析失败\n" + message);
                    updateRunningState(ScreenRiskService.isRunning());
                });
            }
        });
    }

    private void requestRuntimePermissions() {
        if (audioCaptureCheckBox.isChecked()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            startAfterAudioPermission = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            startAfterNotification = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION
            );
            return;
        }
        requestProjectionPermission();
    }

    private void requestProjectionPermission() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            statusView.setText("设备不支持屏幕采集");
            return;
        }
        statusView.setText("请在系统窗口中允许共享屏幕");
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            statusView.setText("未获得屏幕共享授权");
            updateRunningState(false);
            return;
        }

        Intent serviceIntent = new Intent(this, ScreenRiskService.class);
        serviceIntent.setAction(ScreenRiskService.ACTION_START);
        serviceIntent.putExtra(ScreenRiskService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(ScreenRiskService.EXTRA_RESULT_DATA, data);
        serviceIntent.putExtra(
                ScreenRiskService.EXTRA_ENABLE_AUDIO,
                audioCaptureCheckBox.isChecked()
        );
        ContextCompat.startForegroundService(this, serviceIntent);
        statusView.setText("正在启动第三级屏幕检测…");
        updateRunningState(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION && startAfterNotification) {
            startAfterNotification = false;
            requestProjectionPermission();
        }
        if (requestCode == REQUEST_RECORD_AUDIO) {
            startAfterAudioPermission = false;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                audioCaptureCheckBox.setChecked(false);
                Toast.makeText(
                        this,
                        "未授予录音权限，将只检测画面",
                        Toast.LENGTH_LONG
                ).show();
            }
            requestRuntimePermissions();
        }
    }

    private void stopDetection() {
        Intent serviceIntent = new Intent(this, ScreenRiskService.class);
        serviceIntent.setAction(ScreenRiskService.ACTION_STOP);
        startService(serviceIntent);
        statusView.setText("正在停止检测…");
        updateRunningState(false);
    }

    private void openOverlayPermission() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void updateRunningState(boolean isRunning) {
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);
        audioCaptureCheckBox.setEnabled(!isRunning);
        if (!isRunning && statusView.getText().length() == 0) {
            statusView.setText("尚未启动");
        }
    }

    public static String loadServerUrl(Context context) {
        return preferences(context).getString(KEY_SERVER_URL, "");
    }

    public static String loadApiToken(Context context) {
        return preferences(context).getString(KEY_API_TOKEN, "");
    }

    private static void saveSettings(Context context, String serverUrl,
                                     String token, boolean enableAudio) {
        preferences(context).edit()
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_API_TOKEN, token)
                .putBoolean(KEY_ENABLE_AUDIO, enableAudio)
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalizeServerUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
