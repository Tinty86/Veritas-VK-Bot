package com.tinty.Util;

import com.google.gson.JsonObject;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.objects.messages.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VKCallbackHandler {
    private final VKBotConfig config;
    Set<Long> allowedToWriteUsers = ConcurrentHashMap.newKeySet();

    public VKCallbackHandler(VKBotConfig config) {
        this.config = config;
    }

    public String handle(JsonObject json) {
        String secret = json.get("secret").getAsString();
        if (!secret.equals(config.secret)) {
            System.out.println("Invalid secret key");
            return null;
        }

        String type = json.get("type").getAsString();

        return switch (type) {
            case "confirmation" -> config.confirmation;
            case "message_new" -> handleMessageNew(json.getAsJsonObject("object"));
            case "message_event" -> handleMessageEvent(json.getAsJsonObject("object"));
            default -> "ok";
        };
    }

    private String handleMessageNew(JsonObject obj) {
        var msg = obj.getAsJsonObject("message");
        long peerId = msg.get("peer_id").getAsLong();
        String text = msg.get("text").getAsString();

        Keyboard kb = buildInlineKeyboard();

        if (allowedToWriteUsers.contains(peerId)) {
            // Обрабатываем и удаляем разрешение
            allowedToWriteUsers.remove(peerId);

            try {
                config.vk.messages()
                        .sendDeprecated(config.actor)
                        .peerId(peerId)
                        .message("Комментарий получен: " + text)
                        .keyboard(kb)
                        .randomId((int) (Math.random() * Integer.MAX_VALUE))
                        .execute();
            } catch (ApiException | ClientException e) {
                throw new RuntimeException(e);
            }
        } else {
            if (text.equals("Начать")) {
                try {
                    config.vk.messages()
                            .sendDeprecated(config.actor)
                            .peerId(peerId)
                            .message("a")
                            .keyboard(kb)
                            .randomId((int) (Math.random() * Integer.MAX_VALUE))
                            .execute();
                } catch (ApiException | ClientException e) {
                    throw new RuntimeException(e);
                }
            } else {
                // Игнор или предупреждение
                try {
                    config.vk.messages()
                            .sendDeprecated(config.actor)
                            .peerId(peerId)
                            .message("Пожалуйста, сначала нажмите кнопку 'Прокомментировать'")
                            .keyboard(kb)
                            .randomId((int) (Math.random() * Integer.MAX_VALUE))
                            .execute();
                } catch (ApiException | ClientException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return "ok";
    }

    private String handleMessageEvent(JsonObject event) {
        long peerId = event.get("peer_id").getAsLong();
        String payload = event.get("payload").getAsJsonObject().get("action").getAsString();

        if ("allow_comment".equals(payload)) {
            allowedToWriteUsers.add(peerId); // разрешаем писать

            // Обязательно подтвердить callback (иначе кнопка не "моргнёт")
            try {
                config.vk.messages()
                        .sendMessageEventAnswer(config.actor)
                        .eventId(event.get("event_id").getAsString())
                        .userId((long) event.get("user_id").getAsInt())
                        .peerId(peerId)
                        .eventData("{\"type\": \"show_snackbar\", \"text\": \"Теперь вы можете оставить комментарий\"}")
                        .execute();
            } catch (ApiException | ClientException e) {
                throw new RuntimeException(e);
            }

//            // Уведомление
//            try {
//                config.vk.messages()
//                        .sendDeprecated(config.actor)
//                        .peerId(peerId)
//                        .message("Теперь вы можете оставить комментарий")
//                        .randomId((int) (Math.random() * Integer.MAX_VALUE))
//                        .execute();
//            } catch (ApiException | ClientException e) {
//                throw new RuntimeException(e);
//            }
        }

//        System.out.println("Received: " + text + " from: " + peerId);
//
//        Keyboard kb = buildInlineKeyboard();
//
//        try {
//            config.vk.messages()
//                    .sendDeprecated(config.actor)
//                    .peerId(peerId)
//                    .message("Echo: " + text)
//                    .keyboard(kb)
//                    .randomId((int) (Math.random() * Integer.MAX_VALUE))
//                    .execute();
//        } catch (ApiException | ClientException e) {
//            e.printStackTrace();
//        }

        return "ok";
    }

    private Keyboard buildInlineKeyboard() {

        // Действие кнопки
        KeyboardButtonActionCallback commentAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"allow_comment\"}") // что придёт в message.text
                .setLabel("Прокомментировать"); // текст на кнопке

        // Сама кнопка
        KeyboardButton commentButton = new KeyboardButton()
                .setAction(commentAction)
                .setColor(KeyboardButtonColor.PRIMARY); // синий

        List<KeyboardButton> row1 = List.of(commentButton);
        // Можно добавить ещё строки:
        // List<KeyboardButton> row2 = List.of(settingsButton, aboutButton);
        List<List<KeyboardButton>> buttons = List.of(row1 /*, row2, … */);

        // именно inline‑клавиатура

        return new Keyboard()
                .setInline(true) // именно inline‑клавиатура
                .setButtons(buttons);
    }
}
