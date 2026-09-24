package com.example.chapter07.teach.global_view_advanced;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class LessonCardFactory {

    private LessonCardFactory() {
    }

    public static View createCard(Context context, LessonItem item, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        card.setBackgroundColor(Color.parseColor("#F7F8FA"));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(context, 12);
        card.setLayoutParams(cardParams);
        card.setOnClickListener(listener);
        card.setClickable(true);
        card.setFocusable(true);

        TextView tvTag = new TextView(context);
        tvTag.setText(LessonItem.TYPE_PRACTICE.equals(item.getType()) ? "配套练习" : "教学主题");
        tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvTag.setTextColor(Color.WHITE);
        tvTag.setBackgroundColor(Color.parseColor(LessonItem.TYPE_PRACTICE.equals(item.getType()) ? "#FF7043" : "#3F51B5"));
        tvTag.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(item.getOrder() + ". " + item.getTitle());
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Color.parseColor("#222222"));
        tvTitle.setPadding(0, dp(context, 12), 0, dp(context, 8));

        TextView tvFocus = new TextView(context);
        tvFocus.setText(item.getFocus());
        tvFocus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvFocus.setTextColor(Color.parseColor("#444444"));

        TextView tvAction = new TextView(context);
        tvAction.setText("点击查看详细讲解与练习要求");
        tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvAction.setTextColor(Color.parseColor("#1976D2"));
        tvAction.setGravity(Gravity.END);
        tvAction.setPadding(0, dp(context, 12), 0, 0);

        card.addView(tvTag);
        card.addView(tvTitle);
        card.addView(tvFocus);
        card.addView(tvAction);
        return card;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()
        );
    }
}
