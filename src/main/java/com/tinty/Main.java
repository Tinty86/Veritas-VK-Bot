package com.tinty;

import static com.tinty.Firebase.FirebaseHelper.initializeFirebase;
import static spark.Spark.*;
import com.google.gson.JsonParser;
import com.tinty.Bot.VKBotConfig;
import com.tinty.Bot.VKCallbackHandler;

import java.io.IOException;
import java.util.Objects;

public class Main {
    private static final Long GROUP_ID = 231030761L;

    public static void main(String[] args) {
        try {
            initializeFirebase();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        VKBotConfig config = new VKBotConfig(GROUP_ID);
        VKCallbackHandler handler = new VKCallbackHandler(config);

        port(8080);
        post("/vk/callback/", (req, res) -> {
            var json = JsonParser.parseString(req.body()).getAsJsonObject();
            String handleResult;
            if (Objects.equals(handleResult = handler.handle(json), config.confirmation)) {
                res.type("text/plain");
            }
            res.status(200);
            return handleResult;
        });

        System.out.println("Bot is up and running!");
    }
}