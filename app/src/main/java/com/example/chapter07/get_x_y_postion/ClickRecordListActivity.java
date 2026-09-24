package com.example.chapter07.get_x_y_postion;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chapter07.R;
import com.example.chapter07.getposition.ClickRecordDatabaseHelper;
import com.example.chapter07.getposition.ClickRecordDatabaseHelper.ClickRecord;

import java.util.List;

/**
 * 点击记录列表 Activity
 * 功能：展示数据库中保存的所有点击记录的 X、Y 坐标信息
 * 支持滑动查看所有记录
 */
public class ClickRecordListActivity extends AppCompatActivity {

    // ========== 控件声明 ==========
    // RecyclerView：可滑动的列表视图，用于展示所有记录
    private RecyclerView recyclerView;

    // 适配器：将数据绑定到 RecyclerView 的每一项
    private ClickRecordAdapter adapter;

    // 总记录数显示
    private TextView tvTotalCount;

    // 刷新按钮
    private Button btnRefresh;

    // 返回按钮
    private Button btnBack;

    // ========== 数据库帮助类 ==========
    // 用于读取数据库中的点击记录
    private ClickRecordDatabaseHelper dbHelper;

    // ========== Activity 创建时调用 ==========
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置当前页面的布局文件
        setContentView(R.layout.activity_click_record_list);

        // 初始化控件
        initViews();

        // 加载数据库中的记录
        loadRecords();
    }

    // ========== 初始化界面控件 ==========
    private void initViews() {
        // 初始化 RecyclerView
        // RecyclerView 是 Android 用于展示大量数据的可滚动列表组件
        recyclerView = findViewById(R.id.recycler_view);

        // 设置布局管理器
        // LinearLayoutManager 表示列表项垂直排列（从上到下）
        // 这是最常用的布局方式
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 创建适配器实例
        adapter = new ClickRecordAdapter();

        // 将适配器设置给 RecyclerView
        recyclerView.setAdapter(adapter);

        // 初始化其他控件
        tvTotalCount = findViewById(R.id.tv_total_count);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnBack = findViewById(R.id.btn_back);

        // 设置刷新按钮点击事件
        btnRefresh.setOnClickListener(v -> loadRecords());

        // 设置返回按钮点击事件
        btnBack.setOnClickListener(v -> finish());
    }

    // ========== 从数据库加载记录 ==========
    private void loadRecords() {
        // 创建数据库帮助类实例
        dbHelper = new ClickRecordDatabaseHelper(this);

        try {
            // 调用数据库帮助类的方法获取所有记录
            // ClickRecord 是 ClickRecordDatabaseHelper 的内部类
            List<ClickRecord> records = dbHelper.getAllClickRecords();

            // 将数据设置给适配器
            // 适配器会自动更新 RecyclerView 的显示
            adapter.setData(records);

            // 更新总记录数显示
            tvTotalCount.setText("总记录数：" + records.size());

            // 如果没有记录，显示提示
            if (records.isEmpty()) {
                Toast.makeText(this, "暂无记录，请先使用全局放行记录功能", Toast.LENGTH_SHORT).show();
            }

        } finally {
            // 关闭数据库连接
            // 放在 finally 中确保即使出错也会关闭
            dbHelper.close();
        }
    }

    // ========== Activity 重新可见时调用 ==========
    // 比如用户从其他页面返回
    @Override
    protected void onResume() {
        super.onResume();

        // 每次页面可见时重新加载数据
        // 这样可以确保用户返回时看到最新的数据
        loadRecords();
    }
}
