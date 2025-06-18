package com.tinty;

import static spark.Spark.*;
import com.google.gson.JsonParser;
import com.tinty.Util.VKBotConfig;
import com.tinty.Util.VKCallbackHandler;

public class Main {
    private static final Long GROUP_ID = 231030761L;

    public static void main(String[] args) {
        VKBotConfig config = new VKBotConfig(GROUP_ID);
        VKCallbackHandler handler = new VKCallbackHandler(config);

        port(8080);
        post("/vk/callback/", (req, res) -> {
            var json = JsonParser.parseString(req.body()).getAsJsonObject();
            return handler.handle(json);
        });

        System.out.println("Bot is up and running!");
    }
}