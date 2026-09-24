package com.example.chapter07.replay;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;
import com.example.chapter07.getposition.ClickAccessibilityService;
import com.example.chapter07.getposition.ClickRecordDatabaseHelper;
import com.example.chapter07.getposition.DailyClickRecordDatabaseHelper;

import java.util.ArrayList;
import java.util.List;

public class GlobalTouchReplayActivity extends AppCompatActivity {

    private static final long REPLAY_DELAY_MS = 500L;

    private TextView tvStatus;
    private TextView tvCount;
    private Button btnRefresh;
    private Button btnStartReplay;
    private Button btnBack;
    private ListView listView;

    private final List<ClickRecordDatabaseHelper.ClickRecord> records = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private String selectedTimestamp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_global_touch);

        initViews();
        setupListeners();
        loadLatestDatabase();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvCount = findViewById(R.id.tv_click_info);
        btnRefresh = findViewById(R.id.btn_start);
        btnStartReplay = findViewById(R.id.btn_replay);
        btnBack = findViewById(R.id.btn_stop);
        listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
    }

    private void setupListeners() {
        btnRefresh.setText("刷新数据库");
        btnRefresh.setOnClickListener(v -> loadLatestDatabase());
        btnStartReplay.setText("开始回放");
        btnStartReplay.setOnClickListener(v -> startReplay());
        btnBack.setText("返回");
        btnBack.setOnClickListener(v -> finish());
    }

    private void loadLatestDatabase() {
        List<String> timestamps = DailyClickRecordDatabaseHelper.getAllTimestamps(this);
        if (timestamps.isEmpty()) {
            selectedTimestamp = null;
            records.clear();
            adapter.clear();
            tvStatus.setText("未找到点击记录数据库");
            tvCount.setText("点击信息：暂无记录");
            return;
        }

        selectedTimestamp = timestamps.get(0);
        DailyClickRecordDatabaseHelper dbHelper = new DailyClickRecordDatabaseHelper(this, selectedTimestamp);
        records.clear();
        records.addAll(dbHelper.getAllClickRecords());
        dbHelper.close();

        List<String> displayItems = new ArrayList<>();
        for (ClickRecordDatabaseHelper.ClickRecord record : records) {
            displayItems.add("#" + record.id + " (" + record.x + ", " + record.y + ") @ " + record.timestamp);
        }
        adapter.clear();
        adapter.addAll(displayItems);
        adapter.notifyDataSetChanged();

        tvStatus.setText("当前数据库：" + selectedTimestamp);
        tvCount.setText("点击信息：共 " + records.size() + " 条记录");
    }

    private void startReplay() {
        if (!ClickAccessibilityService.isAlive()) {
            new AlertDialog.Builder(this)
                    .setTitle("无障碍服务未开启")
                    .setMessage("回放需要无障碍服务执行全局点击。")
                    .setPositiveButton("去开启", (dialog, which) -> finish())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        if (records.isEmpty()) {
            Toast.makeText(this, "没有可回放的记录", Toast.LENGTH_SHORT).show();
            return;
        }

        replayNext(0);
    }

    private void replayNext(int index) {
        if (index >= records.size()) {
            Toast.makeText(this, "回放完成", Toast.LENGTH_SHORT).show();
            btnStartReplay.setEnabled(true);
            return;
        }

        ClickRecordDatabaseHelper.ClickRecord record = records.get(index);
        btnStartReplay.setEnabled(false);
        tvStatus.setText("正在回放第 " + (index + 1) + " / " + records.size() + " 条");

        tvCount.postDelayed(() -> {
            ClickAccessibilityService.click(record.x, record.y);
            replayNext(index + 1);
        }, REPLAY_DELAY_MS);
    }
}
