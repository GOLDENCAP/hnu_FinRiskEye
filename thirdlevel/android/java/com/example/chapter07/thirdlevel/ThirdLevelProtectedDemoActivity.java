package com.example.chapter07.thirdlevel;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.EdgeToEdgeInsets;
import com.example.chapter07.R;

/**
 * 第三级“受保护画面（自测）”页面。
 *
 * <p>用途：真机验证 FLAG_SECURE 黑屏检测。本页设置
 * {@link WindowManager.LayoutParams#FLAG_SECURE}，系统会禁止截屏与 MediaProjection
 * 采集本页内容，因此检测服务只能拿到黑屏，应显示「检测受限 / 黑屏或受保护画面」
 * 而不是「无风险」。</p>
 *
 * <p>注意：该 Activity 只用于自测，不启动/停止检测服务；从演示页进入，点“返回”回到演示页。</p>
 */
public class ThirdLevelProtectedDemoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // FLAG_SECURE 必须在页面显示前设置：让系统窗口标记为受保护内容，
        // MediaProjection / 截屏只能得到黑屏，用于验证检测端的黑屏判定。
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );
        // 与演示页一致：Android 15+ 强制 edge-to-edge，需要给根布局补系统栏 padding。
        setContentView(R.layout.activity_third_level_protected_demo);
        EdgeToEdgeInsets.apply(this, findViewById(R.id.thirdLevelProtectedDemoRoot));
        findViewById(R.id.thirdLevelProtectedDemoBackButton).setOnClickListener(v -> finish());
        // 自测时屏幕常亮，避免自动锁屏打断真机验证。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
