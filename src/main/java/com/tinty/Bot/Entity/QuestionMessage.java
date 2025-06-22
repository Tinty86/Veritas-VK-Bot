package com.tinty.Bot.Entity;

import com.tinty.Enum.ItemState;
import com.tinty.Firebase.Entity.Answer;
import com.tinty.Firebase.Entity.Question;

import java.util.ArrayList;

public class QuestionMessage extends Question{
    private final ArrayList<Question> questions;
    private ItemState state;

    public QuestionMessage(String content, String type, ArrayList<Answer> answers,
                           ArrayList<Question> questions, ItemState state) {
        super(content, type, answers);
        this.questions = questions;
        this.state = state;
    }

    public ArrayList<Question> getQuestions() {
        return questions;
    }

    public ItemState getState() {
        return state;
    }

    public void setState(ItemState state) {
        this.state = state;
    }
}
