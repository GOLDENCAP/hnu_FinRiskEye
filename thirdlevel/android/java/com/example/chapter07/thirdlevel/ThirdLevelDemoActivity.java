package com.example.chapter07.thirdlevel;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.EdgeToEdgeInsets;
import com.example.chapter07.R;

/**
 * 第三级演示/自测画面页。
 *
 * <p>用途：内置“普通画面”“风险转账页”两张静态测试图，供 12/15 演示与模拟器
 * 联调使用；页面本身不含真实会议内容。注意该页面**不设置 FLAG_SECURE**，
 * 这样 MediaProjection 才能正常采集到演示画面，用于验证“采集→OCR/画面分析→
 * 风险服务→悬浮窗”的完整链路。</p>
 *
 * <p>默认显示普通画面；点击“风险转账页”才切到风险样例，便于演示时先验证
 * “无风险”，再验证“存在风险”。</p>
 */
public class ThirdLevelDemoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_third_level_demo);
        EdgeToEdgeInsets.apply(this, findViewById(R.id.thirdLevelDemoRoot));
        if (getSupportActionBar() != null) {
            // 演示页尽量全屏，隐藏标题栏但保留系统状态栏/导航栏返回能力。
            getSupportActionBar().hide();
        }
        // 演示过程中屏幕常亮，避免模拟器/真机自动锁屏影响采集。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ImageView demoImage = findViewById(R.id.thirdLevelDemoImage);
        findViewById(R.id.thirdLevelDemoNormalButton).setOnClickListener(v ->
                demoImage.setImageResource(R.drawable.third_level_demo_normal));
        findViewById(R.id.thirdLevelDemoRiskButton).setOnClickListener(v ->
                demoImage.setImageResource(R.drawable.third_level_demo_risk));
        findViewById(R.id.thirdLevelDemoCloseButton).setOnClickListener(v -> finish());
        // 受保护画面自测入口：本页设置 FLAG_SECURE，MediaProjection 只能采到黑屏，
        // 用于真机验证“检测受限/黑屏或受保护画面”状态。
        findViewById(R.id.thirdLevelDemoProtectedButton).setOnClickListener(v ->
                startActivity(new Intent(this, ThirdLevelProtectedDemoActivity.class)));
        // 遗留页面测试入口：遗留 Activity 均为 exported=false，adb shell 无法直接启动，
        // 因此内置按钮进入一个简单的遗留页面（getposition.get），用于真机验证
        // FinRiskEyeApplication 的全局 edge-to-edge insets 兜底是否生效。
        // 选 getposition.get 而不是 OpenCvDemoActivity，避免依赖 OpenCV native 初始化。
        findViewById(R.id.thirdLevelDemoLegacyButton).setOnClickListener(v ->
                startActivity(new Intent(this, com.example.chapter07.getposition.get.class)));

        // 默认显示普通画面：演示时先确认“无风险”，再手动切到风险画面。
        demoImage.setImageResource(R.drawable.third_level_demo_normal);
    }
}
