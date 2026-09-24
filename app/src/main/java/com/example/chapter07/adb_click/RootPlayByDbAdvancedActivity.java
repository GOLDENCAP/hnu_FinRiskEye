package com.example.chapter07.adb_click;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chapter07.R;
import com.example.chapter07.getposition.ClickRecordDatabaseHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RootPlayByDbAdvancedActivity extends AppCompatActivity {

    private static final String TAG = "RootPlayByDbAdvanced";
    private static final long CLICK_INTERVAL_MS = 500L;
    private static final long FIRST_CLICK_DELAY_MS = 5000L;
    private static final String DB_PATH = "/data/user/0/com.example.chapter07/databases/";

    private RecyclerView recyclerView;
    private Spinner spinnerDatabase;
    private TextView tvStatus;
    private TextView tvCurrentDb;
    private TextView tvProgress;
    private Button btnPlay;
    private Button btnStop;
    private Button btnBack;

    private ClickRecordAdapter adapter;
    private DatabaseAdapter spinnerAdapter;
    private final List<DatabaseInfo> databaseList = new ArrayList<>();
    private final List<ClickRecordDatabaseHelper.ClickRecord> recordList = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private int currentIndex = 0;
    private boolean isPlaying = false;
    private String currentDatabaseName = null;
    private int firstStartFlag = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        long onCreateStart = SystemClock.elapsedRealtime();
        Log.d(TAG, "onCreate start");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_play_by_db_advanced);
        initViews();
        loadDatabases();
        updateRootStatus();
        Log.d(TAG, "onCreate end, total cost=" + (SystemClock.elapsedRealtime() - onCreateStart) + "ms");
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ClickRecordAdapter();
        recyclerView.setAdapter(adapter);

        spinnerDatabase = findViewById(R.id.spinner_database);
        spinnerAdapter = new DatabaseAdapter(this, databaseList);
        spinnerDatabase.setAdapter(spinnerAdapter);
        spinnerDatabase.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < databaseList.size()) {
                    selectDatabase(databaseList.get(position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        tvStatus = findViewById(R.id.tv_status);
        tvCurrentDb = findViewById(R.id.tv_current_db);
        tvProgress = findViewById(R.id.tv_progress);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnBack = findViewById(R.id.btn_back);

        btnPlay.setOnClickListener(v -> startPlayback());
        btnStop.setOnClickListener(v -> stopPlayback());
        btnBack.setOnClickListener(v -> finish());
    }

    private void loadDatabases() {
        databaseList.clear();
        File dbDir = new File(DB_PATH);
        if (dbDir.exists() && dbDir.isDirectory()) {
            File[] files = dbDir.listFiles((dir, name) -> name.endsWith(".db"));
            if (files != null) {
                for (File file : files) {
                    DatabaseInfo info = new DatabaseInfo();
                    info.name = file.getName();
                    info.path = file.getAbsolutePath();
                    info.recordCount = getRecordCount(file.getAbsolutePath());
                    databaseList.add(info);
                }
            }
        }

        spinnerAdapter.notifyDataSetChanged();
        if (databaseList.isEmpty()) {
            Toast.makeText(this, "未找到数据库文件，请先使用记录功能", Toast.LENGTH_SHORT).show();
        } else {
            spinnerDatabase.setSelection(0);
        }
    }

    private int getRecordCount(String dbPath) {
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM click_records", null);
            int count = 0;
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
            db.close();
            return count;
        } catch (Exception e) {
            Log.e(TAG, "getRecordCount failed, dbPath=" + dbPath, e);
            return 0;
        }
    }

    private void selectDatabase(DatabaseInfo dbInfo) {
        if (isPlaying) {
            stopPlayback();
        }

        currentDatabaseName = dbInfo.name;
        tvCurrentDb.setText("当前数据库：" + dbInfo.name + " (" + dbInfo.recordCount + " 条记录)");
        tvProgress.setText("播放进度：0 / " + dbInfo.recordCount);
        loadRecordsFromDatabase(dbInfo.path);
        btnPlay.setEnabled(dbInfo.recordCount > 0 && RootShellTap.isRootAvailable());
    }

    private void loadRecordsFromDatabase(String dbPath) {
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor cursor = db.query(
                    "click_records",
                    new String[]{"_id", "x_coordinate", "y_coordinate", "timestamp"},
                    null, null, null, null,
                    "timestamp DESC"
            );

            recordList.clear();
            int idIndex = cursor.getColumnIndex("_id");
            int xIndex = cursor.getColumnIndex("x_coordinate");
            int yIndex = cursor.getColumnIndex("y_coordinate");
            int timeIndex = cursor.getColumnIndex("timestamp");

            while (cursor.moveToNext()) {
                ClickRecordDatabaseHelper.ClickRecord record = new ClickRecordDatabaseHelper.ClickRecord();
                record.id = cursor.getInt(idIndex);
                record.x = cursor.getInt(xIndex);
                record.y = cursor.getInt(yIndex);
                record.timestamp = cursor.getString(timeIndex);
                recordList.add(record);
            }

            cursor.close();
            db.close();
            adapter.setData(recordList);
        } catch (Exception e) {
            Log.e(TAG, "loadRecordsFromDatabase failed, dbPath=" + dbPath, e);
            Toast.makeText(this, "加载数据库失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            recordList.clear();
            adapter.setData(recordList);
        }
    }

    private void startPlayback() {
        if (!RootShellTap.isRootAvailable()) {
            Toast.makeText(this, "未检测到可用 root，请先授权 su", Toast.LENGTH_SHORT).show();
            updateRootStatus();
            return;
        }

        if (recordList.isEmpty()) {
            Toast.makeText(this, "当前数据库没有记录", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isPlaying) {
            stopPlayback();
        }

        isPlaying = true;
        currentIndex = 0;
        btnPlay.setEnabled(false);
        btnStop.setEnabled(true);
        spinnerDatabase.setEnabled(false);
        Toast.makeText(this, "开始 root 播放，共 " + recordList.size() + " 条记录", Toast.LENGTH_SHORT).show();

        if (firstStartFlag == 0) {
            MAIN_HANDLER.postDelayed(this::playNextClick, FIRST_CLICK_DELAY_MS);
            firstStartFlag = 1;
        } else {
            playNextClick();
        }
    }

    private void playNextClick() {
        if (!isPlaying) {
            return;
        }
        if (currentIndex >= recordList.size()) {
            playbackFinished();
            return;
        }

        ClickRecordDatabaseHelper.ClickRecord record = recordList.get(currentIndex);
        adapter.setCurrentPlayingIndex(currentIndex);
        recyclerView.smoothScrollToPosition(currentIndex);
        tvProgress.setText("播放进度：" + (currentIndex + 1) + " / " + recordList.size());

        RootShellTap.tap(record.x, record.y);
        currentIndex++;
        handler.postDelayed(this::playNextClick, CLICK_INTERVAL_MS);
    }

    private void playbackFinished() {
        isPlaying = false;
        adapter.setCurrentPlayingIndex(-1);
        btnPlay.setEnabled(!recordList.isEmpty() && RootShellTap.isRootAvailable());
        btnStop.setEnabled(false);
        spinnerDatabase.setEnabled(true);
        Toast.makeText(this, "root 播放完毕！", Toast.LENGTH_SHORT).show();
    }

    private void stopPlayback() {
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
        MAIN_HANDLER.removeCallbacksAndMessages(null);
        adapter.setCurrentPlayingIndex(-1);
        btnPlay.setEnabled(!recordList.isEmpty() && RootShellTap.isRootAvailable());
        btnStop.setEnabled(false);
        spinnerDatabase.setEnabled(true);
        tvProgress.setText("播放进度：0 / " + recordList.size());
    }

    private void updateRootStatus() {
        boolean rootAvailable = RootShellTap.isRootAvailable();
        tvStatus.setText(rootAvailable ? "Root 权限：已可用" : "Root 权限：不可用");
        if (currentDatabaseName != null && !recordList.isEmpty()) {
            btnPlay.setEnabled(rootAvailable);
        } else {
            btnPlay.setEnabled(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRootStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }


    private static class DatabaseInfo {
        String name;
        String path;
        int recordCount;
    }

    private static class DatabaseAdapter extends BaseAdapter {
        private final Context context;
        private final List<DatabaseInfo> list;

        DatabaseAdapter(Context context, List<DatabaseInfo> list) {
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

    private static class ClickRecordAdapter extends RecyclerView.Adapter<ClickRecordAdapter.ViewHolder> {
        private List<ClickRecordDatabaseHelper.ClickRecord> recordList = new ArrayList<>();
        private int currentPlayingIndex = -1;

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvId;
            TextView tvX;
            TextView tvY;
            TextView tvTimestamp;

            ViewHolder(View itemView) {
                super(itemView);
                tvId = itemView.findViewById(R.id.tv_id);
                tvX = itemView.findViewById(R.id.tv_x);
                tvY = itemView.findViewById(R.id.tv_y);
                tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            }
        }

        void setData(List<ClickRecordDatabaseHelper.ClickRecord> list) {
            recordList = new ArrayList<>(list);
            notifyDataSetChanged();
        }

        void setCurrentPlayingIndex(int index) {
            currentPlayingIndex = index;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_click_record, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            ClickRecordDatabaseHelper.ClickRecord record = recordList.get(position);
            holder.tvId.setText(String.valueOf(record.id));
            holder.tvX.setText(String.valueOf(record.x));
            holder.tvY.setText(String.valueOf(record.y));
            holder.tvTimestamp.setText(record.timestamp);
            holder.itemView.setBackgroundColor(position == currentPlayingIndex ? 0xFFE3F2FD : 0xFFFFFFFF);
        }

        @Override
        public int getItemCount() {
            return recordList.size();
        }
    }
}
