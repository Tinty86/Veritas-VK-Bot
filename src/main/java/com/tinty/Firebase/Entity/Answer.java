package com.tinty.Firebase.Entity;

import static com.tinty.Util.CurrentTime.getCurrentTimeStamp;

public class Answer {
    private long senderId;
    private String text;
    private String timeStamp;

    public Answer() {}

    public Answer(long senderId, String text) {
        this.senderId = senderId;
        this.text = text;
        this.timeStamp = getCurrentTimeStamp();
    }

    public long getSenderId() {
        return senderId;
    }

    public void setSenderId(long senderId) {
        this.senderId = senderId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(String timeStamp) {
        this.timeStamp = timeStamp;
    }
}
