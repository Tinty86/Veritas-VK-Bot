package com.tinty.Util;

public class PublicVariables {

    public static final String TRUTH = "truth";
    public static final String DARE = "dare";
    public static final String NEVEREVER = "neverEver";

    private static final String[] games = new String[] {
            TRUTH, DARE, NEVEREVER
    };

    public static String[] getGames() {
        return games;
    }

}
