package com.tinty.Bot;

import com.google.gson.JsonObject;
import com.tinty.Bot.Entity.QuestionMessage;
import com.tinty.Enum.QuestionState;
import com.tinty.Enum.UserState;
import com.tinty.Firebase.Entity.Question;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.objects.messages.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.tinty.Firebase.FirebaseHelper.*;

public class VKCallbackHandler {
    private final VKBotConfig config;
    private Set<Long> allowedToWriteUsers = ConcurrentHashMap.newKeySet();

    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();
    private final Map<Long, QuestionMessage> userMessages = new ConcurrentHashMap<>();

    private static final String groupsMapPath = "groupsMap";

    public VKCallbackHandler(VKBotConfig config) {
        this.config = config;
    }

    public String handle(JsonObject json) {
        String secret = json.get("secret").getAsString();
        if (!secret.equals(config.secret)) {
            System.err.println("Invalid secret key");
            return null;
        }

        String type = json.get("type").getAsString();

        if (type.equals("confirmation")) {
            return config.confirmation;
        }

        new Thread(() -> {
            switch (type) {
                case "message_new" -> handleMessageNew(json.getAsJsonObject("object"));
                case "message_event" -> handleMessageEvent(json.getAsJsonObject("object"));
            }
        }).start();

        return "ok";
    }

    private String handleMessageNew(JsonObject obj) {
        var msg = obj.getAsJsonObject("message");

        if (msg.has("out") && msg.get("out").getAsInt() == 1) {
            return "ok";
        }

        long peerId = msg.get("peer_id").getAsLong();
        String text = msg.get("text").getAsString();

        UserState currentUserState = userStates.getOrDefault(peerId, UserState.NONE);

        if (allowedToWriteUsers.contains(peerId)) {
            // Обрабатываем и удаляем разрешение
            allowedToWriteUsers.remove(peerId);

            // TODO: DEBUG
            Keyboard kb = buildLobbyInlineKeyboard(userMessages.get(peerId).getState());

            sendMessage(peerId, "Комментарий сохранен.", kb);
        } else {
            if (text.equals("Начать")) {
                Keyboard kb = buildStartInlineKeyboard();

                sendMessage(peerId,
                        "Привет, это Veritas бот!\nЕсли вы хотите присоединиться к лобби игры Veritas, то нажмите на кнопку 'Присоединиться' и введите код лобби.",
                        kb);

                userStates.put(peerId, UserState.STARTING_CONVERSATION);
            } else {
                if (currentUserState == UserState.WAITING_FOR_LOBBY_CODE) {
                    Map<String, Object> groupsMap = getMapFromNode(groupsMapPath);

                    if (groupsMap == null) return null;

                    userStates.remove(peerId); // Можно даже удалить, если состояние больше не нужно

                    String groupId;
                    if ((groupId = (String) groupsMap.get(text)) != null) {
                        sendMessage(peerId, "Лобби найдено!");

//                        addParticipant(groupId);

                        getGroupQuestions(groupId, new OnQuestionsRetrievedListener() {
                            @Override
                            public void onSuccess(List<Question> questions) {
                                Keyboard kb = buildLobbyInlineKeyboard(QuestionState.FIRST);
                                QuestionMessage questionMessage = new QuestionMessage(
                                        questions.getFirst().getContent(),
                                        questions.getFirst().getType(),
                                        (ArrayList<Question>) questions,
                                        QuestionState.FIRST
                                );
                                userMessages.put(peerId, questionMessage);
                                sendMessage(peerId, questionMessage.getContent(), kb);
                            }

                            @Override
                            public void onFailure(String error) {
                                System.err.println(error);
                            }
                        });
                    } else {
                        Keyboard kb = buildStartInlineKeyboard();

                        sendMessage(peerId, "Лобби не найдено.\nПроверьте правильность кода.", kb);
                    }
                } else {
                    Keyboard kb;
                    // Игнор или предупреждение
                    if (currentUserState == UserState.STARTING_CONVERSATION) kb = buildStartInlineKeyboard();
                    // TODO: DEBUG
                    else kb = buildLobbyInlineKeyboard(userMessages.get(peerId).getState());
                    sendMessage(peerId, "Пожалуйста, сначала выберите действие.", kb);
                }
            }
        }
        return "ok";
    }

    private String handleMessageEvent(JsonObject event) {
        long peerId = event.get("peer_id").getAsLong();
        String action = event.get("payload").getAsJsonObject().get("action").getAsString();

        switch (action) {
            case "allow_comment" -> {
                allowedToWriteUsers.add(peerId); // разрешаем писать

                // Обязательно подтвердить callback (иначе кнопка не "моргнёт")
                try {
                    sendMessage(peerId, "Теперь вы можете оставить комментарий.");

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
            }
            case "join_lobby" -> {
                sendMessage(peerId, "Введите код лобби:");

                userStates.put(peerId, UserState.WAITING_FOR_LOBBY_CODE); // Устанавливаем состояние для этого пользователя
            }
            case "move_back" -> {
                QuestionMessage questionMessage = userMessages.get(peerId);
                ArrayList<Question> questions = userMessages.get(peerId).getQuestions();
                int questionIndex = questions.indexOf(questionMessage);

                Keyboard kb;
                if (questionIndex - 1 > 0) {
                    Question prevQuestion = questions.get(questions.indexOf(questionMessage) - 1);
                    kb = buildLobbyInlineKeyboard();
                    sendMessage(peerId, prevQuestion.getContent(), kb);

                    userMessages.replace(peerId, new QuestionMessage(
                            prevQuestion.getContent(),
                            prevQuestion.getType(),
                            questions,
                            QuestionState.NONE
                    ));
                } else {
                    kb = buildLobbyInlineKeyboard(QuestionState.FIRST);
                    sendMessage(peerId, "Более ранних вопросов нет.", kb); // Не достижимо, но пусть будет

                    userMessages.replace(peerId, new QuestionMessage(
                            questionMessage.getContent(),
                            questionMessage.getType(),
                            questions,
                            QuestionState.FIRST
                    ));
                }
            }
            case "move_forward" -> {
                QuestionMessage questionMessage = userMessages.get(peerId);

                ArrayList<Question> questions = userMessages.get(peerId).getQuestions();

                int questionIndex = questions.indexOf(questionMessage);

                Keyboard kb;
                if (questionIndex + 1 < questions.size()) {
                    Question nextQuestion = questions.get(questionIndex + 1);
                    kb = buildLobbyInlineKeyboard();
                    sendMessage(peerId, nextQuestion.getContent(), kb);

                    userMessages.replace(peerId, new QuestionMessage(
                            nextQuestion.getContent(),
                            nextQuestion.getType(),
                            questions,
                            QuestionState.NONE
                    ));
                } else {
                    kb = buildLobbyInlineKeyboard(QuestionState.LAST);
                    sendMessage(peerId, "Более поздних вопросов нет.\nТекущий вопрос:\n" + questions.get(questionIndex).getContent(), kb);
                    userMessages.replace(peerId, new QuestionMessage(
                            questionMessage.getContent(),
                            questionMessage.getType(),
                            questions,
                            QuestionState.LAST
                    ));
                }
            }

        }

        return "ok";
    }

    private void sendMessage(Long peerId, String message) {
        try {
            config.vk.messages()
                    .sendDeprecated(config.actor)
                    .peerId(peerId)
                    .message(message)
                    .randomId((int) (Math.random() * Integer.MAX_VALUE))
                    .execute();
        } catch (ApiException | ClientException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendMessage(Long peerId, String message, Keyboard kb) {
        try {
            config.vk.messages()
                    .sendDeprecated(config.actor)
                    .peerId(peerId)
                    .message(message)
                    .keyboard(kb)
                    .randomId((int) (Math.random() * Integer.MAX_VALUE))
                    .execute();
        } catch (ApiException | ClientException e) {
            throw new RuntimeException(e);
        }
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
        KeyboardButton[] buttonRow = new KeyboardButton[4];

        KeyboardButtonActionCallback backAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"move_back\"}") // что придёт в message.text
                .setLabel("⬅️");

        KeyboardButtonActionCallback commentAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"allow_comment\"}") // что придёт в message.text
                .setLabel("\uD83D\uDCDD");

        KeyboardButtonActionCallback readAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"read_action\"}") // что придёт в message.text
                .setLabel("\uD83D\uDCD6");

        KeyboardButtonActionCallback forwardAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"move_forward\"}") // что придёт в message.text
                .setLabel("➡️");

        KeyboardButton backButton = new KeyboardButton()
                .setAction(backAction)
                .setColor(KeyboardButtonColor.DEFAULT);

        KeyboardButton commentButton = new KeyboardButton()
                .setAction(commentAction)
                .setColor(KeyboardButtonColor.DEFAULT);

        KeyboardButton readButton = new KeyboardButton()
                .setAction(readAction)
                .setColor(KeyboardButtonColor.DEFAULT);

        KeyboardButton forwardButton = new KeyboardButton()
                .setAction(forwardAction)
                .setColor(KeyboardButtonColor.DEFAULT);

        buttonRow[0] = backButton;
        buttonRow[1] = commentButton;
        buttonRow[2] = readButton;
        buttonRow[3] = forwardButton;

        List<List<KeyboardButton>> buttons = List.of(List.of(buttonRow));

        return new Keyboard()
                .setInline(true)
                .setButtons(buttons);
    }

    private Keyboard buildLobbyInlineKeyboard(QuestionState state) {
        if (state == QuestionState.NONE) return buildLobbyInlineKeyboard();

        KeyboardButton[] buttonRow = new KeyboardButton[3];

        if (state != QuestionState.FIRST) {
            KeyboardButtonActionCallback backAction = new KeyboardButtonActionCallback()
                    .setType(KeyboardButtonActionCallbackType.CALLBACK)
                    .setPayload("{\"action\":\"move_back\"}") // что придёт в message.text
                    .setLabel("⬅️");

            KeyboardButton backButton = new KeyboardButton()
                    .setAction(backAction)
                    .setColor(KeyboardButtonColor.DEFAULT);

            buttonRow[0] = backButton;
        }

        KeyboardButtonActionCallback commentAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"allow_comment\"}") // что придёт в message.text
                .setLabel("\uD83D\uDCDD");

        KeyboardButtonActionCallback readAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"read_action\"}") // что придёт в message.text
                .setLabel("\uD83D\uDCD6");

        if (state != QuestionState.LAST) {
            KeyboardButtonActionCallback forwardAction = new KeyboardButtonActionCallback()
                    .setType(KeyboardButtonActionCallbackType.CALLBACK)
                    .setPayload("{\"action\":\"move_forward\"}") // что придёт в message.text
                    .setLabel("➡️");

            KeyboardButton forwardButton = new KeyboardButton()
                    .setAction(forwardAction)
                    .setColor(KeyboardButtonColor.DEFAULT);

            buttonRow[2] = forwardButton;
        }



        KeyboardButton commentButton = new KeyboardButton()
                .setAction(commentAction)
                .setColor(KeyboardButtonColor.DEFAULT);

        KeyboardButton readButton = new KeyboardButton()
                .setAction(readAction)
                .setColor(KeyboardButtonColor.DEFAULT);



        if (state == QuestionState.FIRST) {
            buttonRow[0] = commentButton;
            buttonRow[1] = readButton;
        } else {
            buttonRow[1] = commentButton;
            buttonRow[2] = readButton;
        }

        List<List<KeyboardButton>> buttons = List.of(List.of(buttonRow));

        return new Keyboard()
                .setInline(true)
                .setButtons(buttons);
    }

}
