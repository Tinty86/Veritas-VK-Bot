package com.tinty.Bot.Entity;

import com.tinty.Enum.QuestionState;
import com.tinty.Firebase.Entity.Answer;
import com.tinty.Firebase.Entity.Question;

import java.util.ArrayList;

public class QuestionMessage extends Question{
    private final ArrayList<Question> questions;
    private QuestionState state;

    public QuestionMessage(String content, String type, ArrayList<Answer> answers,
                           ArrayList<Question> questions, QuestionState state) {
        super(content, type, answers);
        this.questions = questions;
        this.state = state;
    }

    public ArrayList<Question> getQuestions() {
        return questions;
    }

    public QuestionState getState() {
        return state;
    }

    public void setState(QuestionState state) {
        this.state = state;
    }
}
