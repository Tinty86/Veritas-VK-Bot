package com.tinty.Util;

import static spark.Spark.*;

import io.github.cdimascio.dotenv.Dotenv;

public class VKPass {
    public static void main(String[] args) {
        port(8080);
        Dotenv dotenv = Dotenv.load();
        String CONFIRMATION = dotenv.get("VK_CONFIRMATION");

        System.out.println("VK Bot is running!!!");

        post("/vk/callback/", (req, res) -> {
            var json = com.google.gson.JsonParser.parseString(req.body()).getAsJsonObject();
            String type = json.get("type").getAsString();

            if ("confirmation".equals(type)) {
                res.status(200);
                res.type("text/plain");
                return CONFIRMATION;
            } else {
                return "ok";
            }
        });
    }
}
