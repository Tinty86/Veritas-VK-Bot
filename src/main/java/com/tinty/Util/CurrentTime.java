package com.tinty.Util;

import java.util.Calendar;

public class CurrentTime {
    public static String getCurrentTimeStamp() {
        Calendar now = Calendar.getInstance();
        Integer hour = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);
        return hour + ":" + minute;
    }
}
