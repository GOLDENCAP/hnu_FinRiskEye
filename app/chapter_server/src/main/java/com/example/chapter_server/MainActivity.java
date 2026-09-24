// 声明包名，表示这个类属于 com.example.chapter_server 包
package com.example.chapter_server;

// 导入 Android 系统包，用于保存 Activity 状态（如屏幕旋转时的数据保存）
import android.os.Bundle;

// 导入 Material Design 的 Snackbar 组件，用于显示底部弹出的临时提示消息
import com.google.android.material.snackbar.Snackbar;

// 导入 AppCompatActivity，这是所有支持 ActionBar（标题栏）的 Activity 的基类
import androidx.appcompat.app.AppCompatActivity;

// 导入 View 类，代表用户界面中的基础组件（如按钮、文本框等）
import android.view.View;

// 导入 Navigation 相关的类，用于实现 Fragment 之间的导航跳转
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

// 导入 ViewBinding 自动生成的类，用于更安全、高效地访问布局中的控件
import com.example.chapter_server.databinding.ActivityMainBinding;

// 导入 Menu 相关类，用于创建标题栏的菜单选项
import android.view.Menu;
import android.view.MenuItem;

// 定义 MainActivity 类，继承自 AppCompatActivity
public class MainActivity extends AppCompatActivity {

    // 声明 AppBarConfiguration 对象，用于配置标题栏（ActionBar）的导航行为
    private AppBarConfiguration appBarConfiguration;

    // 声明 ActivityMainBinding 对象，用于通过 ViewBinding 访问布局文件中的控件
    private ActivityMainBinding binding;

    // onCreate 是 Activity 的入口方法，当 Activity 第一次创建时调用
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 调用父类的 onCreate 方法，完成系统级初始化
        super.onCreate(savedInstanceState);

        // 使用 ViewBinding 解析 activity_main.xml 布局文件，生成绑定对象
        binding = ActivityMainBinding.inflate(getLayoutInflater());

        // 将绑定对象中解析出的根布局设置为 Activity 的显示内容
        setContentView(binding.getRoot());

        // 将 toolbar（工具栏）设置为 Activity 的标题栏（ActionBar）
        setSupportActionBar(binding.toolbar);

        // 获取 NavController 对象，用于管理 Fragment 的导航
        // R.id.nav_host_fragment_content_main 是布局中 NavHostFragment 的 ID
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);

        // 创建 AppBarConfiguration 对象，配置标题栏的导航图（navController.getGraph()）
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();

        // 将 NavController 和 AppBarConfiguration 关联到 Activity 的标题栏
        // 这样标题栏会自动显示返回按钮等导航控件
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        // 为悬浮按钮（FloatingActionButton，简称 FAB）设置点击监听器
        binding.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            // 当用户点击悬浮按钮时，执行这个方法
            public void onClick(View view) {
                // 创建一个 Snackbar（底部临时提示条），显示 "Replace with your own action" 文本
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        // 设置提示条的锚点视图，使提示条显示在悬浮按钮上方
                        .setAnchorView(R.id.fab)
                        // 设置一个操作按钮，文本为 "Action"，点击事件为 null（即无操作）
                        .setAction("Action", null)
                        // 显示这个 Snackbar
                        .show();
            }
        });
    }

    // 当 Activity 需要创建选项菜单时调用（即标题栏右上角的菜单）
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 使用 menu_main.xml 文件来填充菜单项
        getMenuInflater().inflate(R.menu.menu_main, menu);
        // 返回 true 表示菜单已创建，可以显示
        return true;
    }

    // 当用户点击菜单中的某个选项时调用
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // 获取被点击菜单项的 ID
        int id = item.getItemId();

        // 判断点击的是否是设置菜单项（R.id.action_settings）
        // noinspection SimplifiableIfStatement 是 IDE 的注解，用于忽略简化 if 语句的警告
        if (id == R.id.action_settings) {
            // 如果是设置按钮，返回 true 表示已处理（但目前没有实际逻辑）
            return true;
        }

        // 如果不是设置按钮，调用父类的处理方法
        return super.onOptionsItemSelected(item);
    }

    // 当用户点击标题栏的“向上”（返回）按钮时调用
    @Override
    public boolean onSupportNavigateUp() {
        // 获取 NavController 对象
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        // 尝试执行向上导航，如果 NavController 处理了导航则返回 true
        // 否则调用父类的 onSupportNavigateUp 方法
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }
}