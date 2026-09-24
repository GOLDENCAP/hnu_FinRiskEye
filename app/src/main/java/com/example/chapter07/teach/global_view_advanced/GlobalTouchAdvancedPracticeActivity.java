package com.example.chapter07.teach.global_view_advanced;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chapter07.R;

import java.util.Locale;

public class GlobalTouchAdvancedPracticeActivity extends AppCompatActivity {

    public static final String EXTRA_ORDER = "practice_order";

    private TextView tvPracticeHeader;
    private TextView tvProblemDescription;
    private TextView tvInputSample;
    private TextView tvExpectedOutput;
    private TextView tvJudgeResult;
    private TextView tvJudgeRule;
    private TextView tvReferenceAnswer;
    private EditText etUserAnswer;
    private Button btnJudge;
    private Button btnShowReference;
    private Button btnOpenLesson;

    private PracticeItem practiceItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_global_touch_advanced_practice);

        initViews();
        loadPractice();
        setupListeners();
        renderPractice();
    }

    private void initViews() {
        tvPracticeHeader = findViewById(R.id.tv_practice_header);
        tvProblemDescription = findViewById(R.id.tv_problem_description);
        tvInputSample = findViewById(R.id.tv_input_sample);
        tvExpectedOutput = findViewById(R.id.tv_expected_output);
        tvJudgeResult = findViewById(R.id.tv_judge_result);
        tvJudgeRule = findViewById(R.id.tv_judge_rule);
        tvReferenceAnswer = findViewById(R.id.tv_reference_answer);
        etUserAnswer = findViewById(R.id.et_user_answer);
        btnJudge = findViewById(R.id.btn_judge_answer);
        btnShowReference = findViewById(R.id.btn_show_reference);
        btnOpenLesson = findViewById(R.id.btn_open_lesson_detail);
    }

    private void loadPractice() {
        int order = getIntent().getIntExtra(EXTRA_ORDER, 1);
        practiceItem = GlobalTouchAdvancedPracticeRepository.findByOrder(order);
        if (practiceItem == null) {
            practiceItem = GlobalTouchAdvancedPracticeRepository.findByOrder(1);
        }
    }

    private void setupListeners() {
        btnJudge.setOnClickListener(v -> judgeAnswer());
        btnShowReference.setOnClickListener(v -> toggleReferenceAnswer());
        btnOpenLesson.setOnClickListener(v -> openLessonDetail());
    }

    private void renderPractice() {
        if (practiceItem == null) {
            return;
        }

        tvPracticeHeader.setText("练习 #" + practiceItem.getOrder() + "\n" + practiceItem.getPracticeTitle());
        tvProblemDescription.setText(practiceItem.getProblemDescription());
        tvInputSample.setText(practiceItem.getInputSample());
        tvExpectedOutput.setText(practiceItem.getExpectedOutput());
        tvJudgeRule.setText(practiceItem.getJudgeRule() + "\n\n答题提示：" + practiceItem.getAnswerHint());
        tvJudgeResult.setText("请先作答，再点击“校验答案”。");
        tvReferenceAnswer.setText("参考答案已隐藏，点击下方按钮查看。");
    }

    private void judgeAnswer() {
        String answer = etUserAnswer.getText().toString().trim();
        if (TextUtils.isEmpty(answer)) {
            tvJudgeResult.setText("未通过：答案区域为空，请先输入你的实现思路或伪代码。");
            return;
        }

        String normalizedAnswer = answer.toLowerCase(Locale.ROOT);
        String[] keywords = practiceItem.getAcceptedKeywords();
        int matchedCount = 0;
        for (String keyword : keywords) {
            if (normalizedAnswer.contains(keyword.toLowerCase(Locale.ROOT))) {
                matchedCount++;
            }
        }

        if (matchedCount == keywords.length) {
            tvJudgeResult.setText("通过：你覆盖了全部判题关键点（" + matchedCount + "/" + keywords.length + "）。\n说明：这是一道教学型练习，当前判题采用关键词覆盖规则。\n你可以继续优化答案结构，让表达更接近正式代码。"
            );
        } else {
            tvJudgeResult.setText("未完全通过：已命中 " + matchedCount + "/" + keywords.length + " 个关键点。\n请对照判题规则补全缺失项，再次提交。\n建议：优先补齐这些核心词对应的逻辑。"
            );
        }
    }

    private void toggleReferenceAnswer() {
        CharSequence current = tvReferenceAnswer.getText();
        if (current != null && current.toString().equals(practiceItem.getReferenceAnswer())) {
            tvReferenceAnswer.setText("参考答案已隐藏，点击下方按钮查看。");
            btnShowReference.setText("显示参考答案");
        } else {
            tvReferenceAnswer.setText(practiceItem.getReferenceAnswer());
            btnShowReference.setText("隐藏参考答案");
        }
    }

    private void openLessonDetail() {
        LessonItem lessonItem = GlobalTouchAdvancedLessonRepository.findByOrder(practiceItem.getOrder());
        if (lessonItem == null) {
            return;
        }

        Intent intent = new Intent(this, GlobalTouchAdvancedTeachDetailActivity.class);
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_ORDER, lessonItem.getOrder());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_TYPE, lessonItem.getType());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_TITLE, lessonItem.getTitle());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_FOCUS, lessonItem.getFocus());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_EXPLANATION, lessonItem.getExplanation());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_EXAMPLES, lessonItem.getExamples());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_PRACTICE_TITLE, lessonItem.getPracticeTitle());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_PRACTICE_REQUIREMENT, lessonItem.getPracticeRequirement());
        intent.putExtra(GlobalTouchAdvancedTeachDetailActivity.EXTRA_EXPECTED_OUTPUT, lessonItem.getExpectedOutput());
        startActivity(intent);
    }
}
