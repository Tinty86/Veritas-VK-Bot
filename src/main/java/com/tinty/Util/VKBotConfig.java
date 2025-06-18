package com.tinty.Util;

import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.httpclient.HttpTransportClient;
import io.github.cdimascio.dotenv.Dotenv;

public class VKBotConfig {
    public final VkApiClient vk;
    public final GroupActor actor;
    public final String confirmation;
    public final String secret;

    public VKBotConfig(Long groupId) {
        Dotenv dotenv = Dotenv.load();
        String token = dotenv.get("VK_TOKEN");
        this.confirmation = dotenv.get("VK_CONFIRMATION");
        this.secret = dotenv.get("SECRET");

        if (token == null || confirmation == null) {
            throw new RuntimeException("VK_TOKEN or VK_CONFIRMATION not set!");
        }

        this.vk = new VkApiClient(HttpTransportClient.getInstance());
        this.actor = new GroupActor(groupId, token);
    }
}