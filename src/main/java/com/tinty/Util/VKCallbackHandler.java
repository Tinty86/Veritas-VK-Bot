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
    private boolean isJoining = false;

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

        if (allowedToWriteUsers.contains(peerId)) {
            // Обрабатываем и удаляем разрешение
            allowedToWriteUsers.remove(peerId);
            Keyboard kb = buildLobbyInlineKeyboard();

            try {
                config.vk.messages()
                        .sendDeprecated(config.actor)
                        .peerId(peerId)
                        .message("Комментарий сохранен.")
                        .keyboard(kb)
                        .randomId((int) (Math.random() * Integer.MAX_VALUE))
                        .execute();
            } catch (ApiException | ClientException e) {
                throw new RuntimeException(e);
            }
        } else {
            if (text.equals("Начать")) {
                Keyboard kb = buildStartInlineKeyboard();
                try {
                    config.vk.messages()
                            .sendDeprecated(config.actor)
                            .peerId(peerId)
                            .message("Здравствуйте, это Veritas бот!\nЕсли вы хотите присоединиться к лобби игры Veritas, то нажмите на кнопку 'Присоединиться' и введите код лобби.")
                            .keyboard(kb)
                            .randomId((int) (Math.random() * Integer.MAX_VALUE))
                            .execute();
                } catch (ApiException | ClientException e) {
                    throw new RuntimeException(e);
                }
            } else {
                if (isJoining) {
                    System.out.println(text);
                } else {
                    // Игнор или предупреждение
                    Keyboard kb = buildLobbyInlineKeyboard();
                    try {
                        config.vk.messages()
                                .sendDeprecated(config.actor)
                                .peerId(peerId)
                                .message("Пожалуйста, сначала выберите действие.")
                                .keyboard(kb)
                                .randomId((int) (Math.random() * Integer.MAX_VALUE))
                                .execute();
                    } catch (ApiException | ClientException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return "ok";
    }

    private String handleMessageEvent(JsonObject event) {
        long peerId = event.get("peer_id").getAsLong();
        String payload = event.get("payload").getAsJsonObject().get("action").getAsString();

        if (payload.equals("allow_comment")) {
            allowedToWriteUsers.add(peerId); // разрешаем писать

            // Обязательно подтвердить callback (иначе кнопка не "моргнёт")
            try {
                config.vk.messages()
                        .sendDeprecated(config.actor)
                        .peerId(peerId)
                        .message("Теперь вы можете оставить комментарий.")
                        .randomId((int) (Math.random() * Integer.MAX_VALUE))
                        .execute();

                config.vk.messages()
                        .sendMessageEventAnswer(config.actor)
                        .eventId(event.get("event_id").getAsString())
                        .userId((long) event.get("user_id").getAsInt())
                        .peerId(peerId)
                        .eventData("{\"type\": \"show_snackbar\", \"text\": \"Теперь вы можете оставить комментарий.\"}")
                        .execute();
            } catch (ApiException | ClientException e) {
                throw new RuntimeException(e);
            }
        } else if (payload.equals("join_lobby")) {
            try {
                config.vk.messages()
                        .sendDeprecated(config.actor)
                        .peerId(peerId)
                        .message("Введите код лобби:")
                        .randomId((int) (Math.random() * Integer.MAX_VALUE))
                        .execute();

                isJoining = true;
            } catch (ApiException | ClientException e) {
                throw new RuntimeException(e);
            }
        }

        return "ok";
    }

    private Keyboard buildStartInlineKeyboard() {
        KeyboardButtonActionCallback joinAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"join_lobby\"}")
                .setLabel("Присоедениться");

        KeyboardButton joinButton = new KeyboardButton()
                .setAction(joinAction)
                .setColor(KeyboardButtonColor.PRIMARY);

        List<KeyboardButton> row1 = List.of(joinButton);
        List<List<KeyboardButton>> buttons = List.of(row1);

        return new Keyboard()
                .setInline(true) // именно inline‑клавиатура
                .setButtons(buttons);
    }

    private Keyboard buildLobbyInlineKeyboard() {
        KeyboardButtonActionCallback commentAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"allow_comment\"}") // что придёт в message.text
                .setLabel("Прокомментировать");

        KeyboardButton commentButton = new KeyboardButton()
                .setAction(commentAction)
                .setColor(KeyboardButtonColor.PRIMARY); // синий

        List<KeyboardButton> row1 = List.of(commentButton);
        List<List<KeyboardButton>> buttons = List.of(row1);

        return new Keyboard()
                .setInline(true)
                .setButtons(buttons);
    }
}
