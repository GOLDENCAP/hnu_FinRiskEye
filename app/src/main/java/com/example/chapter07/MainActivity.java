package com.example.chapter07;  // 声明本类所在的包名为 com.example.chapter07，与 Java 源文件目录结构对应

// ========== Android 框架相关 import ==========
import android.Manifest;                  // 引入权限常量类，Manifest.permission.* 用于运行时申请权限
import android.content.Intent;            // 引入意图类，用于在 Activity 之间跳转
import android.os.Bundle;                 // 引入 Bundle 类，用于在 Activity 创建/恢复时存取状态
import android.view.View;                 // 引入 View 类，本类用作按钮点击监听器参数类型
import android.widget.Button;             // 引入 Button 控件类
import android.widget.EditText;           // 引入 EditText 控件类，登录界面用于输入服务器地址和房间名

import androidx.appcompat.app.AppCompatActivity;  // 引入 AppCompatActivity，AndroidX 提供的带 ActionBar 兼容的 Activity 基类
import com.example.chapter07.databinding.ActivityMainBinding;  // ViewBinding 自动生成的绑定类

// ========== 第三方依赖 ==========
import pub.devrel.easypermissions.EasyPermissions;  // 简化 Android 运行时权限申请流程的第三方库

/**
 * MainActivity：App 启动后显示的主页面。
 *
 * 功能要点：
 *   1. 启动时初始化 WebRTC 信令客户端（SignalClient 单例）
 *   2. 提供"加入房间"按钮，跳转到 peer（音视频通话页面）
 *   3. 提供"获取坐标"按钮（空逻辑）
 *   4. 运行时申请 CAMERA / RECORD_AUDIO 两个权限
 */
public class MainActivity extends AppCompatActivity {  // 继承 AppCompatActivity，具备标题栏兼容能力

    private ActivityMainBinding binding;     // ViewBinding 实例，可直接通过 binding.xxx 访问布局控件

    /**
     * Activity 创建生命周期回调。
     * 用于初始化布局、ViewBinding、信令客户端，以及绑定所有按钮的点击事件。
     */
    @Override                                                       // 注解：覆盖父类方法
    protected void onCreate(Bundle savedInstanceState) {            // savedInstanceState 是 Activity 被销毁前保存的状态，恢复时用
        super.onCreate(savedInstanceState);                         // 必须先调用父类的 onCreate，完成窗口等基础初始化
        binding = ActivityMainBinding.inflate(getLayoutInflater()); // 通过 LayoutInflater 把 activity_main.xml 转为 binding 对象
        setContentView(binding.getRoot());                          // 把 binding 的根布局设置为 Activity 的内容视图

        SignalClient.getInstance().init(this);                      // 初始化信令客户端单例，传入当前 Context（用于加载 HTTPS 证书）

        // 获取"服务器地址"和"房间名"两个输入框的引用（用 final 局部变量以便在内部类中访问）
        final EditText serverEditText = binding.serverEditText;      // 服务器地址输入框
        final EditText roomEditText = binding.roomEditText;          // 房间名输入框

        // 给"加入房间"按钮绑定点击监听器
        binding.joinRoomBtn.setOnClickListener(new View.OnClickListener() {  // 匿名内部类实现 OnClickListener 接口
            @Override
            public void onClick(View view) {                          // 按钮被点击时回调
                String addr = serverEditText.getText().toString();  // 取出服务器地址字符串
                String roomName = roomEditText.getText().toString();// 取出房间名字符串

                if (!"".equals(roomName)) {                          // 房间名非空时才允许进入（避免误进入）
                    Intent intent = new Intent(MainActivity.this, peer.class); // 构造跳转到 peer 的 Intent
                    intent.putExtra("ServerAddr", addr);             // 把服务器地址作为额外数据传递
                    intent.putExtra("RoomName", roomName);           // 把房间名作为额外数据传递
                    startActivity(intent);                          // 启动 peer Activity
                }
            }
        });

        // ========== 运行时权限申请：摄像头 + 麦克风 ==========
        String[] perms = {                                          // 要申请的权限数组
                Manifest.permission.CAMERA,                          // 摄像头权限（视频通话必须）
                Manifest.permission.RECORD_AUDIO                     // 麦克风权限（音频通话必须）
        };

        if (!EasyPermissions.hasPermissions(this, perms)) {         // 检查是否已拥有这两个权限
            EasyPermissions.requestPermissions(                      // 没有则向系统弹出权限申请对话框
                    this,                                           // 当前 Activity 作为回调接收者
                    "Need permissions for camera & microphone",     // 权限说明文字
                    0,                                              // 请求码（用于 onRequestPermissionsResult 中区分请求）
                    perms                                           // 要申请的权限列表
            );
        }
    }

    /**
     * 系统权限申请结果回调。把结果转发给 EasyPermissions 库处理。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); // 先调父类
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this); // 再交给 EasyPermissions 处理
    }

    /**
     * Activity 销毁生命周期回调。目前仅调父类，没有自定义清理逻辑。
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}