package com.example.chapter07.getposition;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.chapter07.R;
import java.util.List;

public class get extends Activity {
    
    private TextView tvCoordinates;
    private TextView tvRecordCount;
    private Button returnBtn;
    private Button viewRecordsBtn;
    private Button clearRecordsBtn;
    private ClickRecordDatabaseHelper dbHelper;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_get);
        
        // 初始化数据库
        dbHelper = new ClickRecordDatabaseHelper(this);
        
        // 获取控件
        tvCoordinates = findViewById(R.id.tv_coordinates);
        tvRecordCount = findViewById(R.id.tv_record_count);
        returnBtn = findViewById(R.id.retuen_btn);
        viewRecordsBtn = findViewById(R.id.view_records_btn);
        clearRecordsBtn = findViewById(R.id.clear_records_btn);
        
        // 设置返回按钮点击事件
        returnBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        
        // 查看记录按钮
        viewRecordsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRecordsDialog();
            }
        });
        
        // 清空记录按钮
        clearRecordsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearAllRecords();
            }
        });
        
        // 更新记录数量显示
        updateRecordCount();
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // 获取点击坐标
            int x = (int) event.getX();
            int y = (int) event.getY();
            
            // 保存到数据库
            long id = dbHelper.insertClickRecord(x, y);
            
            if (id != -1) {
                // 显示保存成功的提示
                String text = "点击位置已保存:\nX = " + x + "\nY = " + y;
                tvCoordinates.setText(text);
                Toast.makeText(this, "坐标 (" + x + ", " + y + ") 已保存", Toast.LENGTH_SHORT).show();
                
                // 更新记录数量
                updateRecordCount();
            } else {
                tvCoordinates.setText("保存失败！");
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
            }
        }
        
        return true;
    }
    
    // 显示所有记录的对话框
    private void showRecordsDialog() {
        List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
        
        if (records.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("点击记录")
                .setMessage("暂无任何点击记录")
                .setPositiveButton("确定", null)
                .show();
            return;
        }
        
        // 构建显示内容
        StringBuilder message = new StringBuilder();
        message.append("共有 ").append(records.size()).append(" 条记录：\n\n");
        
        for (int i = 0; i < Math.min(records.size(), 20); i++) { // 最多显示20条
            ClickRecordDatabaseHelper.ClickRecord record = records.get(i);
            message.append(i + 1).append(". ");
            message.append("坐标：(").append(record.x).append(", ").append(record.y).append(")\n");
            message.append("   时间：").append(record.timestamp).append("\n\n");
        }
        
        if (records.size() > 20) {
            message.append("... 仅显示最近20条记录");
        }
        
        new AlertDialog.Builder(this)
            .setTitle("点击记录")
            .setMessage(message.toString())
            .setPositiveButton("确定", null)
            .setNeutralButton("导出详情", (dialog, which) -> showAllRecordsInDetail())
            .show();
    }
    
    // 显示所有记录的详细信息
    private void showAllRecordsInDetail() {
        List<ClickRecordDatabaseHelper.ClickRecord> records = dbHelper.getAllClickRecords();
        
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无记录", Toast.LENGTH_SHORT).show();
            return;
        }
        
        StringBuilder allRecords = new StringBuilder();
        for (ClickRecordDatabaseHelper.ClickRecord record : records) {
            allRecords.append("ID: ").append(record.id)
                     .append(" | X: ").append(record.x)
                     .append(" | Y: ").append(record.y)
                     .append(" | 时间: ").append(record.timestamp)
                     .append("\n");
        }
        
        new AlertDialog.Builder(this)
            .setTitle("所有记录详情")
            .setMessage(allRecords.toString())
            .setPositiveButton("确定", null)
            .setNegativeButton("复制到剪贴板", (dialog, which) -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("点击记录", allRecords.toString());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            })
            .show();
    }
    
    // 清空所有记录
    private void clearAllRecords() {
        new AlertDialog.Builder(this)
            .setTitle("清空记录")
            .setMessage("确定要清空所有点击记录吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                dbHelper.clearAllRecords();
                updateRecordCount();
                tvCoordinates.setText("所有记录已清空\n点击屏幕开始记录");
                Toast.makeText(this, "已清空所有记录", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    // 更新记录数量显示
    private void updateRecordCount() {
        int count = dbHelper.getRecordCount();
        tvRecordCount.setText("记录数量: " + count);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 关闭数据库连接
        if (dbHelper != null) {
            dbHelper.close();
        }
    }
}