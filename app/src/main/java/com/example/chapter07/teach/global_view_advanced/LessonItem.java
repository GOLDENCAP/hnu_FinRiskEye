package com.example.chapter07.teach.global_view_advanced;

public final class LessonItem {

    public static final String TYPE_LESSON = "lesson";
    public static final String TYPE_PRACTICE = "practice";

    private final int order;
    private final String type;
    private final String title;
    private final String focus;
    private final String explanation;
    private final String examples;
    private final String practiceTitle;
    private final String practiceRequirement;
    private final String expectedOutput;

    public LessonItem(int order,
                      String type,
                      String title,
                      String focus,
                      String explanation,
                      String examples,
                      String practiceTitle,
                      String practiceRequirement,
                      String expectedOutput) {
        this.order = order;
        this.type = type;
        this.title = title;
        this.focus = focus;
        this.explanation = explanation;
        this.examples = examples;
        this.practiceTitle = practiceTitle;
        this.practiceRequirement = practiceRequirement;
        this.expectedOutput = expectedOutput;
    }

    public int getOrder() {
        return order;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getFocus() {
        return focus;
    }

    public String getExplanation() {
        return explanation;
    }

    public String getExamples() {
        return examples;
    }

    public String getPracticeTitle() {
        return practiceTitle;
    }

    public String getPracticeRequirement() {
        return practiceRequirement;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }
}
