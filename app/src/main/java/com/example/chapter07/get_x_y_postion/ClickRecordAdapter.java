package com.example.chapter07.get_x_y_postion;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chapter07.R;
import com.example.chapter07.getposition.ClickRecordDatabaseHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView 的适配器类
 * 作用：将数据库中的 ClickRecord 数据绑定到列表项视图上
 */
public class ClickRecordAdapter extends RecyclerView.Adapter<ClickRecordAdapter.ViewHolder> {

    // 数据源：存储所有的点击记录
    // 注意：ClickRecord 是 ClickRecordDatabaseHelper 的内部类
    private List<ClickRecordDatabaseHelper.ClickRecord> recordList = new ArrayList<>();

    /**
     * ViewHolder：持有列表项视图中的控件引用
     * 避免每次绑定时都调用 findViewById，提升性能
     */
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvId;
        TextView tvX;
        TextView tvY;
        TextView tvTimestamp;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            // 初始化控件引用
            tvId = itemView.findViewById(R.id.tv_id);
            tvX = itemView.findViewById(R.id.tv_x);
            tvY = itemView.findViewById(R.id.tv_y);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
        }
    }

    /**
     * 创建 ViewHolder
     * 当 RecyclerView 需要显示一个新项目时会调用此方法
     */
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 加载列表项的布局文件
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_click_record, parent, false);
        return new ViewHolder(view);
    }

    /**
     * 绑定数据到 ViewHolder
     * 当 RecyclerView 需要显示某个项目时会调用此方法
     */
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // 获取对应位置的记录
        ClickRecordDatabaseHelper.ClickRecord record = recordList.get(position);

        // 设置显示内容
        holder.tvId.setText(String.valueOf(record.id));
        holder.tvX.setText(String.valueOf(record.x));
        holder.tvY.setText(String.valueOf(record.y));
        holder.tvTimestamp.setText(record.timestamp);
    }

    /**
     * 返回数据集的大小
     * 告诉 RecyclerView 一共有多少个项目需要显示
     */
    @Override
    public int getItemCount() {
        return recordList.size();
    }

    /**
     * 更新数据源
     * 调用此方法可以刷新列表显示
     */
    public void setData(List<ClickRecordDatabaseHelper.ClickRecord> newList) {
        this.recordList = newList;
        notifyDataSetChanged(); // 通知 RecyclerView 数据已更改
    }

    /**
     * 获取当前数据集
     */
    public List<ClickRecordDatabaseHelper.ClickRecord> getData() {
        return recordList;
    }
}
