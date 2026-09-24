package com.example.chapter07.teach.global_view_advanced;

public final class PracticeItem {

    private final int order;
    private final String lessonTitle;
    private final String practiceTitle;
    private final String problemDescription;
    private final String inputSample;
    private final String expectedOutput;
    private final String answerHint;
    private final String judgeRule;
    private final String referenceAnswer;
    private final String[] acceptedKeywords;

    public PracticeItem(int order,
                        String lessonTitle,
                        String practiceTitle,
                        String problemDescription,
                        String inputSample,
                        String expectedOutput,
                        String answerHint,
                        String judgeRule,
                        String referenceAnswer,
                        String[] acceptedKeywords) {
        this.order = order;
        this.lessonTitle = lessonTitle;
        this.practiceTitle = practiceTitle;
        this.problemDescription = problemDescription;
        this.inputSample = inputSample;
        this.expectedOutput = expectedOutput;
        this.answerHint = answerHint;
        this.judgeRule = judgeRule;
        this.referenceAnswer = referenceAnswer;
        this.acceptedKeywords = acceptedKeywords;
    }

    public int getOrder() {
        return order;
    }

    public String getLessonTitle() {
        return lessonTitle;
    }

    public String getPracticeTitle() {
        return practiceTitle;
    }

    public String getProblemDescription() {
        return problemDescription;
    }

    public String getInputSample() {
        return inputSample;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public String getAnswerHint() {
        return answerHint;
    }

    public String getJudgeRule() {
        return judgeRule;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public String[] getAcceptedKeywords() {
        return acceptedKeywords;
    }
}
