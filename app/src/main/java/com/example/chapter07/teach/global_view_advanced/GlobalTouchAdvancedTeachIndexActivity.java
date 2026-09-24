package com.example.chapter07.teach.global_view_advanced;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;

import java.util.List;

public class GlobalTouchAdvancedTeachIndexActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_global_touch_advanced_teach_index);

        TextView tvSummary = findViewById(R.id.tv_teach_summary);
        LinearLayout container = findViewById(R.id.lesson_container);

        List<LessonItem> lessons = GlobalTouchAdvancedLessonRepository.buildLessons();
        int lessonCount = lessons.size();

        tvSummary.setText("围绕 GlobalTouchAdvancedActivity 拆解出 "
                + lessonCount
                + " 个教学主题，并为每个主题配套 1 个可量化练习，共 "
                + lessons.size()
                + " 个知识闭环卡片。\n\n"
                + "学习建议：先按顺序看教学，再对照练习独立实现，最后回到原 Activity 做迁移。\n"
                + "覆盖维度：生命周期、控件绑定、输入校验、字符串处理、正则、权限、对话框、Intent、Service、回调、线程切换、状态同步、UI 封装。\n"
                + "练习数量："
                + lessons.size()
                + " 道。\n"
                + "练习特点：每题都有明确输出要求与评判标准。");

        for (LessonItem item : lessons) {
            container.addView(LessonCardFactory.createCard(this, item, v -> openDetail(item)));
        }
    }

    private void openDetail(LessonItem item) {
        Intent intent = new Intent(this, GlobalTouchAdvancedTeachDetailActivity.class);
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_ORDER, item.getOrder());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_TYPE, item.getType());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_TITLE, item.getTitle());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_FOCUS, item.getFocus());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_EXPLANATION, item.getExplanation());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_EXAMPLES, item.getExamples());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_PRACTICE_TITLE, item.getPracticeTitle());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_PRACTICE_REQUIREMENT, item.getPracticeRequirement());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_EXPECTED_OUTPUT, item.getExpectedOutput());
        startActivity(intent);
    }
}
