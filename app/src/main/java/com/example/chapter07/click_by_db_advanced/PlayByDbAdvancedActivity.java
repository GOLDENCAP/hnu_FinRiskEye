package com.example.chapter07.click_by_db_advanced;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chapter07.R;
import com.example.chapter07.getposition.ClickAccessibilityService;
import com.example.chapter07.getposition.ClickRecordDatabaseHelper;
import com.example.chapter07.oneclick.DelayedClickHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放数据库操作升级版 Activity
 * 功能：从指定路径获取所有数据库文件，让用户选择要播放的数据库
 * 然后按顺序播放数据库中的点击记录，间隔 0.5 秒
 */
public class PlayByDbAdvancedActivity extends AppCompatActivity {

    private static final String TAG = "PlayByDbAdvanced";

    // ========== 常量 ==========
    // 点击间隔时间：500 毫秒
    private static final long CLICK_INTERVAL_MS = 500L;

    // 数据库所在路径
    private static final String DB_PATH = "/data/user/0/com.example.chapter07/databases/";

    // ========== 控件声明 ==========
    private RecyclerView recyclerView;
    private Spinner spinnerDatabase;
    private TextView tvStatus;
    private TextView tvCurrentDb;
    private TextView tvProgress;
    private Button btnPlay;
    private Button btnStop;
    private Button btnBack;

    // ========== 数据和适配器 ==========
    private ClickRecordAdapter adapter;
    private DatabaseAdapter spinnerAdapter;
    private List<DatabaseInfo> databaseList = new ArrayList<>();

    // ========== 播放控制 ==========
    private Handler handler = new Handler(Looper.getMainLooper());
    private List<ClickRecordDatabaseHelper.ClickRecord> recordList = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;
    private String currentDatabaseName = null;
    private static final Handler MAIN_HANDLER_PLAY_BY_DB_ADVANCED = new Handler(Looper.getMainLooper());

    private int if_first_begin=0;
    // ========== Activity 创建时调用 ==========
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        long onCreateStart = SystemClock.elapsedRealtime();
        Log.d(TAG, "onCreate start");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play_by_db_advanced);
        Log.d(TAG, "setContentView finished, cost=" + (SystemClock.elapsedRealtime() - onCreateStart) + "ms");

        long initViewsStart = SystemClock.elapsedRealtime();
        initViews();
        Log.d(TAG, "initViews finished, cost=" + (SystemClock.elapsedRealtime() - initViewsStart) + "ms");

        long loadDatabasesStart = SystemClock.elapsedRealtime();
        loadDatabases();
        Log.d(TAG, "loadDatabases finished, cost=" + (SystemClock.elapsedRealtime() - loadDatabasesStart) + "ms");

        long updateStatusStart = SystemClock.elapsedRealtime();
        updateServiceStatus();
        Log.d(TAG, "updateServiceStatus finished, cost=" + (SystemClock.elapsedRealtime() - updateStatusStart) + "ms");
        Log.d(TAG, "onCreate end, total cost=" + (SystemClock.elapsedRealtime() - onCreateStart) + "ms");
    }

    // ========== 初始化界面控件 ==========
    private void initViews() {
        Log.d(TAG, "initViews start");
        // 初始化 RecyclerView
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ClickRecordAdapter();
        recyclerView.setAdapter(adapter);

        // 初始化下拉选择器
        spinnerDatabase = findViewById(R.id.spinner_database);
        spinnerAdapter = new DatabaseAdapter(this, databaseList);
        spinnerDatabase.setAdapter(spinnerAdapter);

        // 设置选择监听器
        spinnerDatabase.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.d(TAG, "spinner onItemSelected position=" + position + ", listSize=" + databaseList.size());
                if (position >= 0 && position < databaseList.size()) {
                    DatabaseInfo dbInfo = databaseList.get(position);
                    selectDatabase(dbInfo);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                Log.d(TAG, "spinner onNothingSelected");
            }
        });

        // 初始化其他控件
        tvStatus = findViewById(R.id.tv_status);
        tvCurrentDb = findViewById(R.id.tv_current_db);
        tvProgress = findViewById(R.id.tv_progress);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnBack = findViewById(R.id.btn_back);

        // 设置按钮点击事件
        btnPlay.setOnClickListener(v -> startPlayback());
        btnStop.setOnClickListener(v -> stopPlayback());
        btnBack.setOnClickListener(v -> finish());
        Log.d(TAG, "initViews end");
    }

    // ========== 加载数据库列表 ==========
    private void loadDatabases() {
        long start = SystemClock.elapsedRealtime();
        Log.d(TAG, "loadDatabases start, path=" + DB_PATH);
        databaseList.clear();

        // 获取数据库目录
        File dbDir = new File(DB_PATH);
        Log.d(TAG, "dbDir exists=" + dbDir.exists() + ", isDirectory=" + dbDir.isDirectory());

        if (dbDir.exists() && dbDir.isDirectory()) {
            // 列出所有 .db 文件
            File[] files = dbDir.listFiles((dir, name) -> name.endsWith(".db"));
            Log.d(TAG, "db files count=" + (files == null ? -1 : files.length));

            if (files != null) {
                for (File file : files) {
                    long fileStart = SystemClock.elapsedRealtime();
                    Log.d(TAG, "begin scan db file=" + file.getAbsolutePath() + ", size=" + file.length());
                    DatabaseInfo info = new DatabaseInfo();
                    info.name = file.getName();
                    info.path = file.getAbsolutePath();
                    info.recordCount = getRecordCount(file.getAbsolutePath());
                    databaseList.add(info);
                    Log.d(TAG, "finish scan db file=" + file.getAbsolutePath() + ", recordCount=" + info.recordCount + ", cost=" + (SystemClock.elapsedRealtime() - fileStart) + "ms");
                }
            }
        }

        // 更新适配器
        spinnerAdapter.notifyDataSetChanged();
        Log.d(TAG, "spinnerAdapter notifyDataSetChanged, dbListSize=" + databaseList.size());

        // 如果没有找到数据库，显示提示
        if (databaseList.isEmpty()) {
            Log.d(TAG, "no database found");
            Toast.makeText(this, "未找到数据库文件，请先使用记录功能", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "default select first database: " + databaseList.get(0).name);
            // 默认选择第一个数据库
            spinnerDatabase.setSelection(0);
        }
        Log.d(TAG, "loadDatabases end, total cost=" + (SystemClock.elapsedRealtime() - start) + "ms");
    }

    // ========== 获取指定数据库的记录数 ==========
    private int getRecordCount(String dbPath) {
        long start = SystemClock.elapsedRealtime();
        Log.d(TAG, "getRecordCount start, dbPath=" + dbPath);
        try {
            // 打开数据库（只读模式）
            SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);

            // 查询记录数
            String tableName = "click_records";
            android.database.Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + tableName, null);
            int count = 0;
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
            db.close();

            Log.d(TAG, "getRecordCount end, dbPath=" + dbPath + ", count=" + count + ", cost=" + (SystemClock.elapsedRealtime() - start) + "ms");
            return count;
        } catch (Exception e) {
            Log.e(TAG, "getRecordCount failed, dbPath=" + dbPath + ", cost=" + (SystemClock.elapsedRealtime() - start) + "ms", e);
            return 0;
        }
    }

    // ========== 选择数据库 ==========
    private void selectDatabase(DatabaseInfo dbInfo) {
        long start = SystemClock.elapsedRealtime();
        Log.d(TAG, "selectDatabase start, name=" + dbInfo.name + ", path=" + dbInfo.path + ", recordCount=" + dbInfo.recordCount + ", isPlaying=" + isPlaying);
        // 如果正在播放，先停止
        if (isPlaying) {
            stopPlayback();
        }

        currentDatabaseName = dbInfo.name;

        // 更新显示
        tvCurrentDb.setText("当前数据库：" + dbInfo.name + " (" + dbInfo.recordCount + " 条记录)");
        tvProgress.setText("播放进度：0 / " + dbInfo.recordCount);

        // 加载该数据库的记录
        loadRecordsFromDatabase(dbInfo.path);

        // 根据是否有记录和无障碍服务状态启用播放按钮
        boolean enablePlay = dbInfo.recordCount > 0 && ClickAccessibilityService.isAlive();
        btnPlay.setEnabled(enablePlay);
        Log.d(TAG, "selectDatabase end, name=" + dbInfo.name + ", enablePlay=" + enablePlay + ", loadedRecords=" + recordList.size() + ", cost=" + (SystemClock.elapsedRealtime() - start) + "ms");
    }

    // ========== 从指定数据库加载记录 ==========
    private void loadRecordsFromDatabase(String dbPath) {
        long start = SystemClock.elapsedRealtime();
        Log.d(TAG, "loadRecordsFromDatabase start, dbPath=" + dbPath);
        try {
            // 打开数据库
            SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);

            // 查询所有记录
            String tableName = "click_records";
            android.database.Cursor cursor = db.query(tableName,
                    new String[]{"_id", "x_coordinate", "y_coordinate", "timestamp"},
                    null, null, null, null, "timestamp DESC");

            recordList.clear();

            int idIndex = cursor.getColumnIndex("_id");
            int xIndex = cursor.getColumnIndex("x_coordinate");
            int yIndex = cursor.getColumnIndex("y_coordinate");
            int timeIndex = cursor.getColumnIndex("timestamp");
            Log.d(TAG, "cursor columns idIndex=" + idIndex + ", xIndex=" + xIndex + ", yIndex=" + yIndex + ", timeIndex=" + timeIndex);

            while (cursor.moveToNext()) {
                ClickRecordDatabaseHelper.ClickRecord record = new ClickRecordDatabaseHelper.ClickRecord();
                record.id = cursor.getInt(idIndex);
                record.x = cursor.getInt(xIndex);
                record.y = cursor.getInt(yIndex);
                record.timestamp = cursor.getString(timeIndex);
                recordList.add(record);
                if (recordList.size() <= 3 || recordList.size() % 100 == 0) {
                    Log.d(TAG, "loadRecords progress, loaded=" + recordList.size() + ", latestRecord=(id=" + record.id + ", x=" + record.x + ", y=" + record.y + ", time=" + record.timestamp + ")");
                }
            }

            cursor.close();
            db.close();

            // 更新适配器
            adapter.setData(recordList);
            Log.d(TAG, "loadRecordsFromDatabase end, dbPath=" + dbPath + ", totalRecords=" + recordList.size() + ", cost=" + (SystemClock.elapsedRealtime() - start) + "ms");

        } catch (Exception e) {
            Log.e(TAG, "loadRecordsFromDatabase failed, dbPath=" + dbPath + ", cost=" + (SystemClock.elapsedRealtime() - start) + "ms", e);
            Toast.makeText(this, "加载数据库失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            recordList.clear();
            adapter.setData(recordList);
        }
    }

    // ========== 开始播放点击 ==========
    private void startPlayback() {
        Log.d(TAG, "startPlayback called, serviceAlive=" + ClickAccessibilityService.isAlive() + ", recordListSize=" + recordList.size() + ", isPlaying=" + isPlaying + ", if_first_begin=" + if_first_begin);
        // 检查无障碍服务是否开启
        if (!ClickAccessibilityService.isAlive()) {
            Log.d(TAG, "startPlayback aborted: accessibility service not alive");
            showAccessibilityDialog();
            return;
        }

        // 检查是否有记录
        if (recordList.isEmpty()) {
            Log.d(TAG, "startPlayback aborted: recordList is empty");
            Toast.makeText(this, "当前数据库没有记录", Toast.LENGTH_SHORT).show();
            return;
        }

        // 如果已经在播放，先停止
        if (isPlaying) {
            Log.d(TAG, "startPlayback detected existing playback, stopping first");
            stopPlayback();
        }

        // 开始播放
        isPlaying = true;
        currentIndex = 0;

        // 更新按钮状态
        btnPlay.setEnabled(false);
        btnStop.setEnabled(true);

        // 禁用数据库选择（播放过程中不能切换）
        spinnerDatabase.setEnabled(false);

        // 显示提示
        Toast.makeText(this, "开始播放，共 " + recordList.size() + " 条记录", Toast.LENGTH_SHORT).show();

        // 开始播放第一个点击

        if(if_first_begin==0){
            Log.d(TAG, "schedule first playNextClick after 5000ms");
            MAIN_HANDLER_PLAY_BY_DB_ADVANCED.postDelayed(() -> {
                Log.d(TAG, "first delayed playNextClick fired");
                playNextClick();
            }, 5000);
            //要注意这里的本质 不是等3s 而是3s后执行这个
            if_first_begin=1;
        } else {
            Log.d(TAG, "startPlayback skipped scheduling because if_first_begin=" + if_first_begin);
        }
        //playNextClick();
    }

    // ========== 播放下一个点击 ==========
    private void playNextClick() {
        Log.d(TAG, "playNextClick called, isPlaying=" + isPlaying + ", currentIndex=" + currentIndex + ", totalRecords=" + recordList.size());
        // 检查是否还在播放
        if (!isPlaying) {
            Log.d(TAG, "playNextClick return: isPlaying is false");
            return;
        }

        // 检查是否已经播放完毕
        if (currentIndex >= recordList.size()) {
            Log.d(TAG, "playNextClick detected playback finished");
            playbackFinished();
            return;
        }

        // 获取当前要播放的记录
        ClickRecordDatabaseHelper.ClickRecord record = recordList.get(currentIndex);
        Log.d(TAG, "playNextClick executing record index=" + currentIndex + ", x=" + record.x + ", y=" + record.y + ", timestamp=" + record.timestamp);

        // 滚动列表到当前项（让用户看到正在播放哪一条）
        adapter.setCurrentPlayingIndex(currentIndex);
        recyclerView.smoothScrollToPosition(currentIndex);

        // 更新播放进度
        tvProgress.setText("播放进度：" + (currentIndex + 1) + " / " + recordList.size());

        // 执行点击
        // DelayedClickHelper.clickAfterDelay(x, y, 0) 立即执行点击
        DelayedClickHelper.clickAfterDelay(record.x, record.y, 0);

        // 更新索引
        currentIndex++;

        // 延迟 0.5 秒后播放下一个
        handler.postDelayed(this::playNextClick, CLICK_INTERVAL_MS);
        Log.d(TAG, "playNextClick scheduled next run after " + CLICK_INTERVAL_MS + "ms, nextIndex=" + currentIndex);
    }

    // ========== 播放完毕 ==========
    private void playbackFinished() {
        Log.d(TAG, "playbackFinished called, totalRecords=" + recordList.size() + ", currentIndex=" + currentIndex);
        isPlaying = false;

        // 重置播放状态
        adapter.setCurrentPlayingIndex(-1);

        // 更新按钮状态
        btnPlay.setEnabled(true);
        btnStop.setEnabled(false);

        // 启用数据库选择
        spinnerDatabase.setEnabled(true);

        // 显示提示
        Toast.makeText(this, "播放完毕！", Toast.LENGTH_SHORT).show();
    }

    // ========== 停止播放 ==========
    private void stopPlayback() {
        Log.d(TAG, "stopPlayback called, isPlaying=" + isPlaying + ", currentIndex=" + currentIndex + ", totalRecords=" + recordList.size() + ", if_first_begin=" + if_first_begin);
        isPlaying = false;

        // 移除所有待执行的延迟任务
        handler.removeCallbacksAndMessages(null);
        MAIN_HANDLER_PLAY_BY_DB_ADVANCED.removeCallbacksAndMessages(null);
        Log.d(TAG, "stopPlayback removed pending callbacks");

        // 重置播放状态
        adapter.setCurrentPlayingIndex(-1);

        // 更新按钮状态
        btnPlay.setEnabled(!recordList.isEmpty());
        btnStop.setEnabled(false);

        // 启用数据库选择
        spinnerDatabase.setEnabled(true);

        // 重置播放进度
        tvProgress.setText("播放进度：0 / " + recordList.size());
    }

    // ========== 显示无障碍服务提示对话框 ==========
    private void showAccessibilityDialog() {
        Log.d(TAG, "showAccessibilityDialog");
        new AlertDialog.Builder(this)
                .setTitle("无障碍服务未开启")
                .setMessage("这个功能依赖无障碍服务来执行全局点击。\n\n请先到设置里开启 Chapter07 的无障碍服务。")
                .setPositiveButton("去开启", (dialog, which) -> {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ========== 更新无障碍服务状态 ==========
    private void updateServiceStatus() {
        boolean serviceAlive = ClickAccessibilityService.isAlive();
        Log.d(TAG, "updateServiceStatus, serviceAlive=" + serviceAlive + ", currentDatabaseName=" + currentDatabaseName + ", recordListSize=" + recordList.size());
        if (serviceAlive) {
            tvStatus.setText("无障碍服务：已开启");
            // 根据记录数和无障碍服务状态启用播放按钮
            if (currentDatabaseName != null && !recordList.isEmpty()) {
                btnPlay.setEnabled(true);
            }
        } else {
            tvStatus.setText("无障碍服务：未开启");
            btnPlay.setEnabled(false);
        }
    }

    // ========== Activity 重新可见时调用 ==========
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        updateServiceStatus();
    }

    // ========== Activity 销毁时调用 ==========
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy start");
        super.onDestroy();
        // 停止播放
        stopPlayback();
        Log.d(TAG, "onDestroy end");
    }

    // ========== 数据库信息类 ==========
    private static class DatabaseInfo {
        String name;       // 数据库文件名
        String path;       // 数据库完整路径
        int recordCount;   // 记录数量

        @Override
        public String toString() {
            return name + " (" + recordCount + " 条)";
        }
    }

    // ========== Spinner 适配器 ==========
    private static class DatabaseAdapter extends BaseAdapter {
        private Context context;
        private List<DatabaseInfo> list;

        public DatabaseAdapter(Context context, List<DatabaseInfo> list) {
            this.context = context;
            this.list = list;
        }

        @Override
        public int getCount() {
            return list.size();
        }

        @Override
        public Object getItem(int position) {
            return list.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(android.R.layout.simple_spinner_item, parent, false);
            }

            TextView textView = (TextView) convertView;
            DatabaseInfo info = list.get(position);
            textView.setText(info.name + " (" + info.recordCount + " 条记录)");

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(android.R.layout.simple_spinner_dropdown_item, parent, false);
            }

            TextView textView = (TextView) convertView;
            DatabaseInfo info = list.get(position);
            textView.setText(info.name + " (" + info.recordCount + " 条记录)");

            return convertView;
        }
    }

    // ========== RecyclerView 适配器 ==========
    private static class ClickRecordAdapter extends RecyclerView.Adapter<ClickRecordAdapter.ViewHolder> {

        private List<ClickRecordDatabaseHelper.ClickRecord> recordList = new ArrayList<>();
        private int currentPlayingIndex = -1;

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvId;
            TextView tvX;
            TextView tvY;
            TextView tvTimestamp;
            View itemContainer;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvId = itemView.findViewById(R.id.tv_id);
                tvX = itemView.findViewById(R.id.tv_x);
                tvY = itemView.findViewById(R.id.tv_y);
                tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
                itemContainer = itemView;
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_click_record, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ClickRecordDatabaseHelper.ClickRecord record = recordList.get(position);

            holder.tvId.setText(String.valueOf(record.id));
            holder.tvX.setText(String.valueOf(record.x));
            holder.tvY.setText(String.valueOf(record.y));
            holder.tvTimestamp.setText(record.timestamp);

            // 如果是当前播放项，高亮显示
            if (position == currentPlayingIndex) {
                holder.itemContainer.setBackgroundColor(0xFFFFFF00); // 黄色背景
            } else {
                holder.itemContainer.setBackgroundColor(0xFFFFFFFF); // 白色背景
            }
        }

        @Override
        public int getItemCount() {
            return recordList.size();
        }

        public void setData(List<ClickRecordDatabaseHelper.ClickRecord> newList) {
            this.recordList = newList;
            this.currentPlayingIndex = -1;
            notifyDataSetChanged();
        }

        public void setCurrentPlayingIndex(int index) {
            int oldIndex = this.currentPlayingIndex;
            this.currentPlayingIndex = index;

            // 刷新旧的播放项
            if (oldIndex >= 0 && oldIndex < recordList.size()) {
                notifyItemChanged(oldIndex);
            }

            // 刷新新的播放项
            if (index >= 0 && index < recordList.size()) {
                notifyItemChanged(index);
            }
        }
    }
}
