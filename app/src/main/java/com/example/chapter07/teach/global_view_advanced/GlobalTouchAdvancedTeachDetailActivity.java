package com.example.chapter07.teach.global_view_advanced;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;

public class GlobalTouchAdvancedTeachDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ORDER = "extra_order";
    public static final String EXTRA_TYPE = "extra_type";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_FOCUS = "extra_focus";
    public static final String EXTRA_EXPLANATION = "extra_explanation";
    public static final String EXTRA_EXAMPLES = "extra_examples";
    public static final String EXTRA_PRACTICE_TITLE = "extra_practice_title";
    public static final String EXTRA_PRACTICE_REQUIREMENT = "extra_practice_requirement";
    public static final String EXTRA_EXPECTED_OUTPUT = "extra_expected_output";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_global_touch_advanced_teach_detail);

        TextView tvHeader = findViewById(R.id.tv_detail_header);
        TextView tvFocus = findViewById(R.id.tv_detail_focus);
        TextView tvExplanation = findViewById(R.id.tv_detail_explanation);
        TextView tvExamples = findViewById(R.id.tv_detail_examples);
        TextView tvPracticeTitle = findViewById(R.id.tv_detail_practice_title);
        TextView tvPracticeRequirement = findViewById(R.id.tv_detail_practice_requirement);
        TextView tvExpectedOutput = findViewById(R.id.tv_detail_expected_output);
        Button btnOpenPractice = findViewById(R.id.btn_open_practice);

        int order = getIntent().getIntExtra(EXTRA_ORDER, 0);
        String type = getIntent().getStringExtra(EXTRA_TYPE);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String focus = getIntent().getStringExtra(EXTRA_FOCUS);
        String explanation = getIntent().getStringExtra(EXTRA_EXPLANATION);
        String examples = getIntent().getStringExtra(EXTRA_EXAMPLES);
        String practiceTitle = getIntent().getStringExtra(EXTRA_PRACTICE_TITLE);
        String practiceRequirement = getIntent().getStringExtra(EXTRA_PRACTICE_REQUIREMENT);
        String expectedOutput = getIntent().getStringExtra(EXTRA_EXPECTED_OUTPUT);

        String typeLabel = LessonItem.TYPE_PRACTICE.equals(type) ? "练习" : "教学";
        tvHeader.setText(typeLabel + " #" + order + "\n" + safe(title));
        tvFocus.setText(safe(focus));
        tvExplanation.setText(safe(explanation));
        tvExamples.setText(safe(examples));
        tvPracticeTitle.setText(safe(practiceTitle));
        tvPracticeRequirement.setText(safe(practiceRequirement));
        tvExpectedOutput.setText(safe(expectedOutput));

        btnOpenPractice.setOnClickListener(v -> {
            Intent intent = new Intent(this, GlobalTouchAdvancedPracticeActivity.class);
            intent.putExtra(GlobalTouchAdvancedPracticeActivity.EXTRA_ORDER, order);
            startActivity(intent);
        });
    }

    private String safe(String value) {
        return value == null ? "暂无内容" : value;
    }
}
