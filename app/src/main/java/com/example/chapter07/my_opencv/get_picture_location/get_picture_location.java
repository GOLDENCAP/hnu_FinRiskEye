// 声明当前 Java 文件所属的包路径，便于 Java/Android 按目录组织类
package com.example.chapter07.my_opencv.get_picture_location;

// 导入 Bitmap 类，用来在内存中表示一张位图图片
import android.graphics.Bitmap;
// 导入 BitmapFactory 类，用来把资源文件解码成 Bitmap 对象
import android.graphics.BitmapFactory;
// 导入 Color 类，用来设置颜色值，例如白色背景
import android.graphics.Color;
// 导入 Rect 类，用来保存一个矩形区域的位置和边界信息
import android.graphics.Rect;
// 导入 Bundle 类，用于 Activity 创建时接收系统传入的状态数据
import android.os.Bundle;
// 导入 Log 类，用来向 Logcat 输出调试日志
import android.util.Log;
// 导入 Button 控件类，用于处理按钮显示与点击
import android.widget.Button;
// 导入 ImageView 控件类，用于在界面上显示图片
import android.widget.ImageView;
// 导入 TextView 控件类，用于显示调试信息和状态文字
import android.widget.TextView;

// 导入 AndroidX 的 AppCompatActivity，作为当前页面的基类
import androidx.appcompat.app.AppCompatActivity;

// 导入当前模块自动生成的资源索引类，用于访问 layout、id、raw 等资源
import com.example.chapter07.R;

/**
 * 运行思路详细说明：
 * 1. 这个 Activity 的目标是把 res/raw/picture_1 图片读取出来，并在页面上的 ImageView 中显示。
 * 2. 页面启动后，先加载布局文件，再通过 findViewById 拿到 ImageView、TextView、Button 三个控件。
 * 3. 然后对 ImageView 做基础初始化：设置白色背景、设置缩放方式、先清空当前图片。
 * 4. 接着通过 BitmapFactory.decodeResource 从 raw 目录读取 picture_1，并生成 Bitmap 对象。
 * 5. 读取完成后，会调用 validateBitmap 对 Bitmap 做基础校验，确认它不是 null、没有被回收、宽高有效。
 * 6. 如果图片校验失败，就把失败信息写进调试输出区，并停止后续流程。
 * 7. 如果图片校验成功，就给按钮绑定点击事件。按钮每点击一次，都会进入 togglePicture 方法。
 * 8. togglePicture 方法内部通过 isImageVisible 这个布尔值判断当前图片是否已经显示。
 * 9. 如果图片当前已经显示，则清空 ImageView 中的 drawable，实现“隐藏图片”的效果。
 * 10. 如果图片当前未显示，则把 bitmap 设置给 ImageView，实现“显示图片”的效果。
 * 11. 同时，程序会把每一步关键流程都写入 debugBuilder，并通过 TextView 显示到页面上，方便排查问题。
 * 12. 页面销毁时，在 onDestroy 中主动回收 Bitmap，避免图片对象长期占用内存。
 * 13. 整个类的核心目标可以概括为：加载图片 -> 校验图片 -> 点击切换显示/隐藏 -> 输出完整调试信息 -> 页面销毁时释放资源。
 */
public class get_picture_location extends AppCompatActivity {

    // 定义日志标签，后续写 Logcat 时会使用这个 TAG，方便过滤日志
    private static final String TAG = "get_picture_location";

    // 声明 ImageView 变量，用于引用布局里的图片显示控件
    private ImageView imageView;
    // 声明 TextView 变量，用于引用布局里的调试信息显示控件
    private TextView resultView;
    // 声明 Button 变量，用于引用布局里的切换图片按钮
    private Button toggleButton;
    // 声明 Bitmap 变量，用于保存从资源里读取出来的图片对象
    private Bitmap bitmap;
    // 声明布尔变量，记录当前图片是否正在界面中显示，默认 false 表示未显示
    private boolean isImageVisible = false;
    // 声明一个 StringBuilder，用于持续拼接调试日志文本，避免频繁字符串相加
    private final StringBuilder debugBuilder = new StringBuilder();

    // 重写 Activity 的 onCreate 方法，这是页面创建时最先执行的核心入口
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 先调用父类 onCreate，让系统完成 Activity 基础初始化
        super.onCreate(savedInstanceState);
        // 加载当前页面对应的布局文件 activity_get_picture_location.xml
        setContentView(R.layout.activity_get_picture_location);

        // 记录一条调试日志，表示 onCreate 已经开始执行
        debugStep("onCreate start");

        // 根据布局中的 id 获取 ImageView 控件实例，并赋值给成员变量 imageView
        imageView = findViewById(R.id.iv_picture);
        // 根据布局中的 id 获取 TextView 控件实例，并赋值给成员变量 resultView
        resultView = findViewById(R.id.tv_picture_result);
        // 根据布局中的 id 获取 Button 控件实例，并赋值给成员变量 toggleButton
        toggleButton = findViewById(R.id.btn_toggle_picture_location);

        // 输出控件绑定结果，方便确认三个控件是否都成功从布局中找到
        debugStep("view bind complete"
                + " | imageView=" + (imageView != null)
                + " | resultView=" + (resultView != null)
                + " | toggleButton=" + (toggleButton != null));

        // 如果三个控件中有任意一个为 null，说明布局 id 不匹配或页面初始化异常
        if (imageView == null || resultView == null || toggleButton == null) {
            // 记录控件绑定失败的调试日志
            debugStep("view bind failed, stop init");
            // 把当前累积的调试信息渲染到页面上，方便用户看到错误状态
            renderDebugText();
            // 直接结束 onCreate，避免后续空指针错误
            return;
        }

        // 给 ImageView 设置白色背景，避免未显示图片时背景透明看不清区域
        imageView.setBackgroundColor(Color.WHITE);
        // 设置 ImageView 的缩放模式为 FIT_CENTER，保证图片等比例缩放并居中显示
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 允许 ImageView 根据图片内容调整边界，增强显示兼容性
        imageView.setAdjustViewBounds(true);
        // 初始化时先清空 ImageView 当前绑定的 drawable，确保页面刚打开时不显示旧图
        imageView.setImageDrawable(null);

        // 从 res/raw 目录读取 picture_1 资源，并解码生成 Bitmap 对象
        bitmap = BitmapFactory.decodeResource(getResources(), R.raw.picture_1);
        // 把解码得到的 Bitmap 基本状态写入调试信息，例如宽高、配置、字节数等
        debugBitmapState("after decodeResource", bitmap);

        // 如果 Bitmap 校验失败，就不再继续执行后续显示逻辑
        if (!validateBitmap(bitmap)) {
            // 把 bitmap 置为 null，明确当前图片资源不可用
            bitmap = null;
            // 记录一条调试日志，说明图片校验失败并停止初始化
            debugStep("bitmap validate failed, stop init");
            // 把调试信息显示在页面上
            renderDebugText();
            // 提前返回，结束 onCreate
            return;
        }

        // 给按钮设置点击事件监听器，用户每次点击按钮都会执行这里的逻辑
        toggleButton.setOnClickListener(view -> {
            // 记录按钮被点击时的状态，方便观察当前图片是否已显示
            debugStep("toggleButton clicked | isImageVisible=" + isImageVisible);
            // 调用切换图片显示/隐藏的方法
            togglePicture();
        });

        // 把一个任务投递到 ImageView 的消息队列中，确保在布局完成后再获取尺寸信息
        imageView.post(() -> {
            // 输出布局完成后的 ImageView 尺寸与位置，方便调试界面布局问题
            debugStep("post after layout"
                    + " | width=" + imageView.getWidth()
                    + " | height=" + imageView.getHeight()
                    + " | x=" + imageView.getX()
                    + " | y=" + imageView.getY());
            // 创建一个 Rect 对象，用于接收 ImageView 在屏幕中的可见区域
            Rect rect = new Rect();
            // 获取 ImageView 当前在屏幕上的可见矩形，并返回是否可见
            boolean visible = imageView.getGlobalVisibleRect(rect);
            // 把可见状态和矩形信息写入调试日志
            debugStep("global rect after layout"
                    + " | visible=" + visible
                    + " | rect=" + rect.toShortString());
            // 把更新后的调试信息渲染到页面上
            renderDebugText();
        });

        // onCreate 最后主动刷新一次页面上的调试信息
        renderDebugText();
    }

    /**
     * 校验传入的 Bitmap 是否可用。
     * 校验内容包括：
     * 1. 是否为 null
     * 2. 是否已经 recycle
     * 3. 宽高是否大于 0
     * 4. 如果通过，则把关键属性写入调试日志
     */
    private boolean validateBitmap(Bitmap targetBitmap) {
        // 如果目标 Bitmap 为 null，说明图片解码失败
        if (targetBitmap == null) {
            // 记录 Bitmap 为 null 的调试信息
            debugStep("validateBitmap fail | bitmap is null");
            // 返回 false 表示校验失败
            return false;
        }

        // 如果 Bitmap 已经被 recycle，说明它已经不可再使用
        if (targetBitmap.isRecycled()) {
            // 记录 Bitmap 已被回收的调试信息
            debugStep("validateBitmap fail | bitmap recycled");
            // 返回 false 表示校验失败
            return false;
        }

        // 如果 Bitmap 的宽或高小于等于 0，说明图片内容异常或解码不完整
        if (targetBitmap.getWidth() <= 0 || targetBitmap.getHeight() <= 0) {
            // 记录图片尺寸无效的调试信息
            debugStep("validateBitmap fail | invalid size="
                    + targetBitmap.getWidth() + "x" + targetBitmap.getHeight());
            // 返回 false 表示校验失败
            return false;
        }

        // 读取 Bitmap 的像素配置，例如 ARGB_8888、RGB_565 等
        Bitmap.Config config = targetBitmap.getConfig();
        // 记录 Bitmap 校验成功时的关键属性信息
        debugStep("validateBitmap success"
                + " | width=" + targetBitmap.getWidth()
                + " | height=" + targetBitmap.getHeight()
                + " | config=" + config
                + " | byteCount=" + targetBitmap.getByteCount());
        // 返回 true 表示校验通过
        return true;
    }

    /**
     * 切换图片显示状态：
     * - 如果当前显示中，则隐藏图片
     * - 如果当前未显示，则显示图片
     */
    private void togglePicture() {
        // 如果 bitmap 为 null，说明当前没有可用图片资源，直接停止操作
        if (bitmap == null) {
            // 记录 bitmap 为空的调试信息
            debugStep("togglePicture stop | bitmap is null");
            // 刷新调试文字到界面
            renderDebugText();
            // 结束方法执行
            return;
        }

        // 如果图片当前已经显示，则执行隐藏逻辑
        if (isImageVisible) {
            // 清空 ImageView 中当前显示的 drawable，从而达到隐藏图片的效果
            imageView.setImageDrawable(null);
            // 更新状态位，表示当前图片已经隐藏
            isImageVisible = false;
            // 修改按钮文字，提示用户下次点击可以重新加载图片
            toggleButton.setText("加载图片");
            // 记录隐藏完成的调试信息
            debugStep("hide complete | image cleared from ImageView");
            // 把最新调试信息渲染到页面上
            renderDebugText();
            // 结束方法执行，避免继续进入显示分支
            return;
        }

        // 如果图片当前未显示，则把显示任务投递到 ImageView 的消息队列，保证视图状态稳定后再执行
        imageView.post(() -> {
            // 记录即将显示图片时 ImageView 的尺寸信息
            debugStep("show runnable start"
                    + " | width=" + imageView.getWidth()
                    + " | height=" + imageView.getHeight());
            // 把 Bitmap 设置到 ImageView 上，真正触发图片显示
            imageView.setImageBitmap(bitmap);
            // 创建一个 Rect 对象，用来接收 ImageView 当前的屏幕可见区域
            Rect rect = new Rect();
            // 获取当前 ImageView 在屏幕中的可见矩形，并记录是否可见
            boolean visible = imageView.getGlobalVisibleRect(rect);
            // 更新状态位，表示图片已经显示
            isImageVisible = true;
            // 修改按钮文字，提示用户当前可以点击来隐藏图片
            toggleButton.setText("隐藏图片");
            // 记录显示完成后的关键状态，包括是否可见、矩形范围、drawable 是否存在等
            debugStep("show runnable end"
                    + " | visible=" + visible
                    + " | rect=" + rect.toShortString()
                    + " | imageViewDrawable=" + (imageView.getDrawable() != null)
                    + " | isImageVisible=" + isImageVisible);
            // 把更新后的调试信息显示到页面上
            renderDebugText();
        });
    }

    /**
     * 输出 Bitmap 的详细状态信息，用于排查图片是否正常解码。
     */
    private void debugBitmapState(String stage, Bitmap targetBitmap) {
        // 如果 Bitmap 为 null，直接记录对应阶段的空状态
        if (targetBitmap == null) {
            // 记录当前阶段 bitmap 为 null 的调试信息
            debugStep(stage + " | bitmap=null");
            // 提前返回，不再继续读取属性
            return;
        }

        // 输出 Bitmap 的宽、高、配置、是否已回收、像素密度、字节数等信息
        debugStep(stage
                + " | width=" + targetBitmap.getWidth()
                + " | height=" + targetBitmap.getHeight()
                + " | config=" + targetBitmap.getConfig()
                + " | recycled=" + targetBitmap.isRecycled()
                + " | density=" + targetBitmap.getDensity()
                + " | byteCount=" + targetBitmap.getByteCount());
    }

    /**
     * 统一的调试输出方法：
     * 1. 写入 Logcat
     * 2. 拼接到页面显示文本中
     */
    private void debugStep(String message) {
        // 把调试信息输出到 Logcat，日志级别为 Debug
        Log.d(TAG, message);
        // 把调试信息追加到 StringBuilder 中，并额外加两个换行便于阅读
        debugBuilder.append(message).append("\n\n");
    }

    /**
     * 把累积的调试日志文本显示到页面中的 TextView 上。
     */
    private void renderDebugText() {
        // 只有当 resultView 不为 null 时，才允许设置文本，避免空指针异常
        if (resultView != null) {
            // 把调试日志字符串去掉首尾空白后显示到 TextView 上
            resultView.setText(debugBuilder.toString().trim());
        }
    }

    // 重写 Activity 销毁方法，用于在页面关闭时释放图片资源
    @Override
    protected void onDestroy() {
        // 先记录 onDestroy 开始执行时 bitmap 是否为空
        debugStep("onDestroy start | bitmapNull=" + (bitmap == null));
        // 调用父类 onDestroy，执行系统标准销毁流程
        super.onDestroy();
        // 如果 bitmap 不为 null 且尚未被 recycle，则主动释放其底层内存
        if (bitmap != null && !bitmap.isRecycled()) {
            // 调用 recycle 手动回收 Bitmap 占用的底层图像内存
            bitmap.recycle();
            // 记录 Bitmap 已经在 onDestroy 中被回收
            debugStep("bitmap recycled in onDestroy");
            // 把 bitmap 引用置空，避免后续误用已回收对象
            bitmap = null;
        }
        // 销毁前最后再尝试刷新一次调试信息到页面上
        renderDebugText();
    }
}
