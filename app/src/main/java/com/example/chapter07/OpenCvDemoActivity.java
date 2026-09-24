package com.example.chapter07;  // 声明本类所在的包名为 com.example.chapter07

// ========== Android 框架相关 import ==========
import android.os.Bundle;                 // 引入 Bundle，用于 Activity 状态存取
import android.widget.TextView;           // 引入 TextView 控件，用于显示 OpenCV 初始化结果

import androidx.appcompat.app.AppCompatActivity;  // AppCompatActivity，AndroidX 提供的 Activity 基类

// ========== OpenCV 相关 import ==========
import org.opencv.android.OpenCVLoader;   // OpenCV Android 加载器，负责加载 native .so 库
import org.opencv.core.CvType;            // OpenCV 数据类型常量（如 CV_8UC1 = 8 位无符号单通道）
import org.opencv.core.Mat;               // OpenCV 矩阵类，表示图像或数值矩阵
import org.opencv.core.Scalar;            // OpenCV 标量类，用于构造带初始值的 Mat

/**
 * OpenCvDemoActivity：OpenCV 初始化演示页。
 *
 * 功能：
 *   1. 加载 OpenCV native 库（通过 OpenCVLoader.initLocal()）
 *   2. 创建一个 2x3 的 8 位单通道矩阵，全部填入 7
 *   3. 在 TextView 上显示矩阵的行列数、数据类型和首个像素值，用于验证 OpenCV 是否可用
 */
public class OpenCvDemoActivity extends AppCompatActivity {  // 继承 AppCompatActivity

    /**
     * Activity 创建生命周期回调。完成 UI 初始化、OpenCV 加载、Mat 创建与展示。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {     // savedInstanceState 用于恢复上次状态
        super.onCreate(savedInstanceState);                  // 调用父类 onCreate 完成基础初始化

        // ===== 1. 动态创建一个 TextView 作为页面内容（没用 XML 布局） =====
        TextView statusView = new TextView(this);            // 构造一个 TextView，this 是 Context
        int padding = (int) (24 * getResources().getDisplayMetrics().density); // 24dp 转 px（密度无关像素）
        statusView.setPadding(padding, padding, padding, padding); // 设置上下左右内边距
        statusView.setTextSize(16f);                         // 设置字号 16sp
        setContentView(statusView);                          // 把 TextView 设置为 Activity 内容

        // ===== 2. 加载 OpenCV native 库 =====
        boolean initSuccess = OpenCVLoader.initLocal();      // 加载 OpenCVManager apk 中的本地库（"本地方式"，不依赖 Market）
        if (!initSuccess) {                                  // 加载失败
            statusView.setText("OpenCV 初始化失败，无法调用 Java API。"); // 提示失败信息
            return;                                          // 提前返回，不执行后续 Mat 操作
        }

        // ===== 3. 创建一个 2 行 3 列、8 位无符号单通道的 Mat，全部填入 7 =====
        Mat mat = new Mat(2, 3, CvType.CV_8UC1, new Scalar(7)); // rows=2, cols=3, type=CV_8UC1, initial value=7
        StringBuilder builder = new StringBuilder();          // 构造字符串拼接器
        builder.append("OpenCV 初始化成功\n")                  // 第一行：成功提示
                .append("rows=").append(mat.rows()).append('\n')   // 第二行：行数（rows=2）
                .append("cols=").append(mat.cols()).append('\n')   // 第三行：列数（cols=3）
                .append("type=").append(mat.type()).append('\n')   // 第四行：数据类型（type=0 表示 CV_8UC1）
                .append("samplePixel=").append((int) mat.get(0, 0)[0]); // 第五行：首个像素值（应是 7）
        statusView.setText(builder.toString());              // 把构造好的字符串设置到 TextView
        mat.release();                                       // 释放 native 内存（OpenCV Mat 是 native 资源，必须手动 release）
    }
}