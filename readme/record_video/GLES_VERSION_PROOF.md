# 暖色调渲染 GLES 版本验证

## 验证日期
2026-08-05 02:48

## 验证结论
进行暖色调渲染时，**启用了 GLES 3.0**，使用的是 **GLES 3.0 Shader**。

## 关键日志证据

### 1. 暖色调功能开启
```
08-05 02:48:18.819  4930  4930 I CallActivity: Shader mode: ON (warm color rendering)
```

### 2. GL 版本检测与选择
```
08-05 02:48:18.951  4930  4973 I YuvGLSurfaceView: GL Version: OpenGL ES 3.1 v1
08-05 02:48:18.951  4930  4973 I YuvGLSurfaceView: Using GLES 3.0
```
**说明**：设备支持 GLES 3.1，但代码配置 (`setEGLContextClientVersion(3)`) 使用 GLES 3.0。

### 3. Shader 初始化
```
08-05 02:48:18.951  4930  4973 D YuvGLSurfaceView: initShaders, GLES30: true
08-05 02:48:18.978  4930  4973 D YuvGLSurfaceView: Shader initialized, programId: 3
```
**说明**：由于 `mGLES30Supported = true`，系统使用了 GLES 3.0 版本的 Shader（`#version 300 es`）。

## 代码依据

参考 `YuvGLSurfaceView.java` 第 103-131 行的 `onSurfaceCreated` 方法：

```java
@Override
public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
    if (DEBUG) Log.d(TAG, "onSurfaceCreated");

    // 检测 GLES 版本
    String versionString = GLES20.glGetString(GLES20.GL_VERSION);
    if (DEBUG) Log.i(TAG, "GL Version: " + versionString);
    mGLES30Supported = versionString != null && versionString.contains("OpenGL ES 3.");

    if (DEBUG) Log.i(TAG, "Using GLES " + (mGLES30Supported ? "3.0" : "2.0"));

    // ...
    initShaders();
    // ...
}
```

日志 `GL Version: OpenGL ES 3.1 v1` 包含字符串 `"OpenGL ES 3."`，因此 `mGLES30Supported` 被设为 `true`，最终使用了 **GLES 3.0 Shader**。
