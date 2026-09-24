# 从零实现 MainActivity —— 渐进式构建指南

> **目标读者**：Android 入门者，会写简单 Activity，对 ViewBinding / 权限申请 / Intent 跳转不熟的人。
>
> **本文目标**：把 `app/src/main/java/com/example/chapter07/MainActivity.java`（285 行）从"空 Activity"演进到一个包含权限申请、ViewBinding、信令客户端初始化、和 20+ 个功能演示按钮的"应用主菜单"页面。

---

## 目录

1. [这个 Activity 到底要做什么](#1-这个-activity-到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 空 Activity](#3-阶段-1--空-activity)
4. [阶段 2 —— setContentView + 加载布局](#4-阶段-2--setcontentview--加载布局)
5. [阶段 3 —— 用 ViewBinding 访问控件](#5-阶段-3--用-viewbinding-访问控件)
6. [阶段 4 —— 初始化信令客户端](#6-阶段-4--初始化信令客户端)
7. [阶段 5 —— 申请运行时权限](#7-阶段-5--申请运行时权限)
8. [阶段 6 —— "加入房间"按钮](#8-阶段-6--加入房间按钮)
9. [阶段 7 —— 第一个演示按钮（"获取坐标"）](#9-阶段-7--第一个演示按钮获取坐标)
10. [阶段 8 —— 复制粘贴：N 个演示按钮](#10-阶段-8--复制粘贴n-个演示按钮)
11. [阶段 9 —— 动态改按钮文字](#11-阶段-9--动态改按钮文字)
12. [阶段 10 —— 加 onRequestPermissionsResult 转发](#12-阶段-10--加-onrequestpermissionsresult-转发)
13. [阶段 11 —— 加 onDestroy](#13-阶段-11--加-ondestroy)
14. [完整最终代码对照](#14-完整最终代码对照)
15. [演进回顾表](#15-演进回顾表)
16. [常见问答](#16-常见问答)

---

## 1. 这个 Activity 到底要做什么

一句话：**应用启动后的主菜单页面，集成信令客户端初始化、权限申请、20+ 个功能演示按钮**。

页面布局（activity_main.xml）：
- 服务器地址输入框
- 房间名输入框
- "加入房间"按钮
- 一堆演示按钮（无障碍、回放、OpenCV、RTSP、录音等）

这个 Activity 本身**不实现任何业务逻辑**，只是导航入口 —— 每个按钮跳到对应 Activity。

---

## 2. 前置知识速查

### 2.1 ViewBinding 是什么

- Android Gradle 插件提供的功能
- 自动为每个 XML 布局生成一个绑定类
- 例：`activity_main.xml` → `ActivityMainBinding`
- 用法：`binding.joinRoomBtn.setOnClickListener(...)` 替代 `findViewById(R.id.joinRoomBtn)`

### 2.2 Android 运行时权限

- Android 6.0+ 引入
- 危险权限必须在运行时申请
- `EasyPermissions` 是第三方库，简化申请流程

### 2.3 Intent 跳转

```java
Intent intent = new Intent(this, TargetActivity.class);
intent.putExtra("key", value);  // 可选：传参数
startActivity(intent);
```

### 2.4 Lambda 表达式

```java
button.setOnClickListener(v -> {  // Lambda
    // 替代匿名内部类
});
```

---

## 3. 阶段 1 —— 空 Activity

### 3.1 本阶段目标
创建 MainActivity 类，能编译能运行。

### 3.2 完整代码

```java
package com.example.chapter07;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
}
```

### 3.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `extends AppCompatActivity` | 基类 |

---

## 4. 阶段 2 —— setContentView + 加载布局

### 4.1 本阶段目标
用 ViewBinding 加载 `activity_main.xml` 布局。

### 4.2 遇到的新问题
**问题 1：ViewBinding 怎么用？**
- Android Studio 自动生成 `ActivityMainBinding` 类
- `ActivityMainBinding.inflate(layoutInflater)` → binding 对象
- `binding.getRoot()` → 拿到根 View

**问题 2：为什么用 ViewBinding 而不是 findViewById？**
- 类型安全（编译时检查）
- 代码更简洁
- 不需要写一堆 `findViewById(R.id.xxx)`

### 4.3 完整代码

```java
import android.os.Bundle;
import com.example.chapter07.databinding.ActivityMainBinding;

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
}
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `onCreate` 重写 | 生命周期入口 |
| `ActivityMainBinding.inflate(...)` | 加载布局 |
| `setContentView(binding.getRoot())` | 设置页面 |

---

## 5. 阶段 3 —— 用 ViewBinding 访问控件

### 5.1 本阶段目标
把 binding 存到字段，方便后续访问。

### 5.2 完整代码

```java
private ActivityMainBinding binding;

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    // 现在可以用 binding.xxx 访问任何控件
}
```

### 5.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `binding` 字段 | 在多个方法里共享 binding |

---

## 6. 阶段 6 —— 初始化信令客户端

### 6.1 本阶段目标
调用 `SignalClient.getInstance().init(this)` 初始化信令客户端。

### 6.2 遇到的新问题
**问题 1：为什么要 init？**
- 答：`SignalClient` 是单例，但需要 Context 才能加载 HTTPS 证书。
- init 把 Context 注入给单例。

**问题 2：必须在 onCreate 里调吗？**
- 是的，至少要在 joinRoom 之前。
- 当前实现：在 onCreate 早期就调（保证后续按钮可立即用）。

### 6.3 完整代码

```java
SignalClient.getInstance().init(this);
```

---

## 7. 阶段 7 —— 申请运行时权限

### 7.1 本阶段目标
用 EasyPermissions 申请 CAMERA + RECORD_AUDIO 两个权限。

### 7.2 遇到的新问题
**问题 1：为什么要权限？**
- WebRTC 音视频通话需要 CAMERA（摄像头）+ RECORD_AUDIO（麦克风）。
- 没这两个权限，peer Activity 进房间后会没画面/没声音。

**问题 2：为什么用 EasyPermissions？**
- 第三方库，简化回调处理。
- 替代系统原生的 `requestPermissions(...)`。

### 7.3 完整代码

```java
import pub.devrel.easypermissions.EasyPermissions;
import android.Manifest;

String[] perms = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
};

if (!EasyPermissions.hasPermissions(this, perms)) {
    EasyPermissions.requestPermissions(this,
            "Need permissions for camera & microphone", 0, perms);
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| 权限数组 | 列出需要的权限 |
| `hasPermissions` 检查 | 避免重复申请 |
| `requestPermissions` 申请 | 弹出系统对话框 |

---

## 8. 阶段 8 —— "加入房间"按钮

### 8.1 本阶段目标
实现"加入房间"按钮的点击逻辑：跳转到 peer Activity。

### 8.2 遇到的新问题
**问题 1：为什么用 final 局部变量？**
- 答：onClick 在内部类里访问 serverEditText / roomEditText。
- Java 要求被内部类访问的局部变量必须是 final 或 effectively final。

**问题 2：为什么要 `!"".equals(roomName)`？**
- 防止空房间名进入。
- `"".equals(x)` 比 `x.equals("")` 安全（x 是 null 也不会崩）。

### 8.3 完整代码

```java
final EditText serverEditText = binding.serverEditText;
final EditText roomEditText = binding.roomEditText;

binding.joinRoomBtn.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View view) {
        String addr = serverEditText.getText().toString();
        String roomName = roomEditText.getText().toString();

        if (!"".equals(roomName)) {
            Intent intent = new Intent(MainActivity.this, peer.class);
            intent.putExtra("ServerAddr", addr);
            intent.putExtra("RoomName", roomName);
            startActivity(intent);
        }
    }
});
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `final EditText` | 在 onClick 里访问输入框 |
| `setOnClickListener` | 注册点击 |
| `Intent` + `startActivity` | 跳转 |

---

## 9. 阶段 9 —— 第一个演示按钮（"获取坐标"）

### 9.1 本阶段目标
实现第一个演示按钮，跳转到"获取坐标"Activity。

### 9.2 遇到的新问题
**问题 1：演示按钮有 20 多个，怎么写？**
- 每个按钮 4 行代码（findViewById + setOnClickListener）
- 可以用循环提取公共逻辑，但本项目直接复制粘贴
- 这样可读性高，每按钮都是独立样板

### 9.3 完整代码

```java
Button switch1 = binding.getpostion;  // 注意：id 是 "getpostion"（少一个 i）
switch1.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View view) {
        Intent intent = new Intent(MainActivity.this, get.class);
        startActivity(intent);
    }
});
```

---

## 10. 阶段 10 —— 复制粘贴：N 个演示按钮

### 10.1 本阶段目标
把剩余 19 个演示按钮的样板代码复制粘贴过来。

### 10.2 完整代码

每个按钮都是这种结构（不同 Activity）：
```java
Button btnXxx = findViewById(R.id.btn_xxx);
btnXxx.setOnClickListener(v -> {
    Intent intent = new Intent(MainActivity.this, XxxActivity.class);
    startActivity(intent);
});
```

具体有：
- 无障碍演示
- 点击回放
- 后台回放
- 后台记录
- 点击记录
- 全局放行记录
- 1秒后点击 200,200
- 全局点击回放
- 画板
- 点击记录列表
- DB 播放
- DB 高级播放
- 全局触摸高级
- 全局触摸高级教学
- Root DB 播放
- OpenCV 全部功能
- OpenCV 演示
- 获取周围图片
- 获取点击和图片
- RTSP 播放器
- 0829 录音测试

### 10.3 经验教训
**重复代码不一定坏**：
- 维护时只改一处
- 不同按钮的"特殊逻辑"（如传参数）方便加
- 比起"过度抽象"，重复更易读

---

## 11. 阶段 11 —— 动态改按钮文字

### 11.1 本阶段目标
给部分按钮动态设置文字（用 `setText`）。

### 11.2 遇到的新问题
**问题 1：为什么不在 XML 里直接写？**
- 因为文字可能是动态的（如"录制"/"停止录制"切换）
- 或者需要根据用户配置变化

### 11.3 完整代码

```java
binding.btnBackgroundRecord.setText("交替记录");
binding.btnGlobalTouchPassThrough.setText("全局放行记录");
binding.btnOneClick200.setText("1秒后点击 200,200");
binding.btnGlobalTouchReplay.setText("全局点击回放");
```

---

## 12. 阶段 12 —— 加 onRequestPermissionsResult 转发

### 12.1 本阶段目标
把系统权限申请回调转发给 EasyPermissions。

### 12.2 完整代码

```java
@Override
public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
}
```

### 12.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `onRequestPermissionsResult` | 系统回调 |
| `EasyPermissions.onRequestPermissionsResult(...)` | 转发给库处理 |

---

## 13. 阶段 13 —— 加 onDestroy

### 13.1 本阶段目标
重写 onDestroy 做清理（当前仅调父类）。

### 13.2 完整代码

```java
@Override
protected void onDestroy() {
    super.onDestroy();
}
```

### 13.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `onDestroy` 空实现 | 为未来清理预留 |

---

## 14. 完整最终代码对照

到阶段 13 为止，与 `app/src/main/java/com/example/chapter07/MainActivity.java` 完全一致。所有代码已在前面 13 个阶段给出。

---

## 15. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 空 Activity | 5 | 基类 |
| 2 | 加载布局 | 15 | 页面骨架 |
| 3 | binding 字段 | 20 | 共享 binding |
| 4 | init 信令 | 22 | 单例初始化 |
| 5 | 权限申请 | 35 | 危险权限 |
| 6 | 加入房间按钮 | 50 | 主功能入口 |
| 7 | 第一个演示按钮 | 60 | 样板代码 |
| 8 | 复制 N 个按钮 | 200 | 所有演示入口 |
| 9 | 改按钮文字 | 220 | 动态文字 |
| 10 | 权限回调转发 | 230 | EasyPermissions |
| 11 | onDestroy | 285 | 完整生命周期 |

---

## 16. 常见问答

### Q1：为什么用 ViewBinding 而不是 DataBinding？
A：DataBinding 更强大但更复杂，需要 `<layout>` 标签包裹、binding 表达式等。ViewBinding 只做"找控件"，对当前项目够用。

### Q2：按钮 onClick 用 Lambda 还是匿名类？
A：Lambda 更简洁（`v -> { ... }`），但 Lambda 不能访问外部非 final 变量。当前项目混用两种风格。

### Q3：为什么不在 onDestroy 里释放资源？
A：MainActivity 不持有需要释放的资源（信令客户端是单例，不归 MainActivity 管）。

### Q4：按钮太多，能不能用 RecyclerView + 列表？
A：可以。但如果每个按钮的点击逻辑不同，列表反而要写更多代码。
当前项目选"垂直 LinearLayout 一堆按钮"，简单粗暴。

### Q5：为什么 "btnBackgroundRecord.setText('交替记录')" 而不是 XML 里写？
A：因为这个按钮在不同场景下文字不同（"交替记录"/"正常记录"等）。
动态改文字比维护多个 XML 布局简单。

### Q6：权限申请一定要用 EasyPermissions 吗？
A：不一定。可以直接用系统 API `ActivityCompat.requestPermissions(...)`。
EasyPermissions 只是简化回调。

### Q7：为什么 Intent 里传 String 而不是用静态常量？
A：传 String 是简单的 demo 写法。生产环境应该定义常量：
```java
public static final String EXTRA_SERVER = "ServerAddr";
public static final String EXTRA_ROOM = "RoomName";
```
