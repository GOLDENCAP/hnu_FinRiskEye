    package com.example.chapter07.click_by_db;

    import android.app.AlertDialog;
    import android.content.Intent;
    import android.os.Bundle;
    import android.os.Handler;
    import android.os.Looper;
    import android.provider.Settings;
    import android.view.LayoutInflater;
    import android.view.View;
    import android.view.ViewGroup;
    import android.widget.Button;
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

    import java.util.ArrayList;
    import java.util.List;

    /**
     * 通过数据库播放点击的 Activity
     * 功能：从数据库读取点击记录，然后按顺序播放每个点击，间隔 0.5 秒
     */
    public class PlayByDbActivity extends AppCompatActivity {

        // ========== 常量 ==========
        // 点击间隔时间：500 毫秒
        private static final long CLICK_INTERVAL_MS = 500L;

        // ========== 控件声明 ==========
        private RecyclerView recyclerView;
        private TextView tvStatus;
        private TextView tvInfo;
        private Button btnPlay;
        private Button btnStop;
        private Button btnBack;

        // ========== 数据和适配器 ==========
        private ClickRecordAdapter adapter;
        private ClickRecordDatabaseHelper dbHelper;

        // ========== 播放控制 ==========
        private Handler handler = new Handler(Looper.getMainLooper());
        private List<ClickRecordDatabaseHelper.ClickRecord> recordList = new ArrayList<>();
        private int currentIndex = 0;
        private boolean isPlaying = false;
        private  int if_first_begin = 0;

        private static final Handler MAIN_HANDLER_PLAY_BY_DB = new Handler(Looper.getMainLooper());

        // ========== Activity 创建时调用 ==========
        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_play_by_db);

            initViews();
            loadRecords();
            updateServiceStatus();
        }

        // ========== 初始化界面控件 ==========
        private void initViews() {
            // 初始化 RecyclerView
            recyclerView = findViewById(R.id.recycler_view);
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            adapter = new ClickRecordAdapter();
            recyclerView.setAdapter(adapter);

            // 初始化其他控件
            tvStatus = findViewById(R.id.tv_status);
            tvInfo = findViewById(R.id.tv_info);
            btnPlay = findViewById(R.id.btn_play);
            btnStop = findViewById(R.id.btn_stop);
            btnBack = findViewById(R.id.btn_back);

            // 设置按钮点击事件
            btnPlay.setOnClickListener(v -> startPlayback());
            btnStop.setOnClickListener(v -> stopPlayback());
            btnBack.setOnClickListener(v -> finish());
        }

        // ========== 从数据库加载记录 ==========
        private void loadRecords() {
            dbHelper = new ClickRecordDatabaseHelper(this);

            try {
                // 获取所有点击记录
                recordList = dbHelper.getAllClickRecords();

                // 更新适配器数据
                adapter.setData(recordList);

                // 更新统计信息
                tvInfo.setText("总记录数：" + recordList.size());

                // 根据是否有记录来启用播放按钮
                btnPlay.setEnabled(!recordList.isEmpty() && ClickAccessibilityService.isAlive());

                // 如果没有记录，显示提示
                if (recordList.isEmpty()) {
                    Toast.makeText(this, "数据库中没有记录", Toast.LENGTH_SHORT).show();
                }

            } finally {
                dbHelper.close();
            }
        }

        // ========== 开始播放点击 ==========
        private void startPlayback() {

            // 检查无障碍服务是否开启
            if (!ClickAccessibilityService.isAlive()) {
                showAccessibilityDialog();
                return;
            }

            // 如果已经在播放，先停止
            if (isPlaying) {
                stopPlayback();
            }

            // 开始播放
            isPlaying = true;
            currentIndex = 0;

            // 更新按钮状态
            btnPlay.setEnabled(false);
            btnStop.setEnabled(true);

            // 显示提示
            Toast.makeText(this, "开始播放，共 " + recordList.size() + " 条记录", Toast.LENGTH_SHORT).show();
            if(if_first_begin==0){
                MAIN_HANDLER_PLAY_BY_DB.postDelayed(() -> {playNextClick();}, 5000);
                //要注意这里的本质 不是等3s 而是3s后执行这个
                if_first_begin=1;
            }
            // 开始播放第一个点击

        }

        // ========== 播放下一个点击 ==========
        private void playNextClick() {
            // 检查是否还在播放
            if (!isPlaying) {
                return;
            }

            // 检查是否已经播放完毕
            if (currentIndex >= recordList.size()) {
                // 播放完毕
                playbackFinished();
                return;
            }

            // 获取当前要播放的记录
            ClickRecordDatabaseHelper.ClickRecord record = recordList.get(currentIndex);

            // 滚动列表到当前项（让用户看到正在播放哪一条）
            adapter.setCurrentPlayingIndex(currentIndex);
            recyclerView.smoothScrollToPosition(currentIndex);

            // 执行点击
            // DelayedClickHelper.clickAfterDelay(x, y, 0) 立即执行点击
            DelayedClickHelper.clickAfterDelay(record.x, record.y, 0);

            // 更新索引
            currentIndex++;

            // 延迟 0.5 秒后播放下一个
            handler.postDelayed(this::playNextClick, CLICK_INTERVAL_MS);
        }

        // ========== 播放完毕 ==========
        private void playbackFinished() {
            isPlaying = false;

            // 重置播放状态
            adapter.setCurrentPlayingIndex(-1);

            // 更新按钮状态
            btnPlay.setEnabled(true);
            btnStop.setEnabled(false);

            // 显示提示
            Toast.makeText(this, "播放完毕！", Toast.LENGTH_SHORT).show();
        }

        // ========== 停止播放 ==========
        private void stopPlayback() {
            isPlaying = false;

            // 移除所有待执行的延迟任务
            handler.removeCallbacksAndMessages(null);

            // 重置播放状态
            adapter.setCurrentPlayingIndex(-1);

            // 更新按钮状态
            btnPlay.setEnabled(!recordList.isEmpty());
            btnStop.setEnabled(false);
        }

        // ========== 显示无障碍服务提示对话框 ==========
        private void showAccessibilityDialog() {
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
            if (ClickAccessibilityService.isAlive()) {
                tvStatus.setText("无障碍服务：已开启");
                btnPlay.setEnabled(!recordList.isEmpty());
            } else {
                tvStatus.setText("无障碍服务：未开启");
                btnPlay.setEnabled(false);
            }
        }

        // ========== Activity 重新可见时调用 ==========
        @Override
        protected void onResume() {
            super.onResume();
            updateServiceStatus();
        }

        // ========== Activity 销毁时调用 ==========
        @Override
        protected void onDestroy() {
            super.onDestroy();
            // 停止播放
            stopPlayback();
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
