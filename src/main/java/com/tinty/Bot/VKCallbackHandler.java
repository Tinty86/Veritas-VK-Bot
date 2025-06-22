package com.tinty.Bot;

import com.google.gson.JsonObject;
import com.tinty.Bot.Entity.QuestionMessage;
import com.tinty.Enum.ItemState;
import com.tinty.Enum.UserState;
import com.tinty.Firebase.Entity.Answer;
import com.tinty.Firebase.Entity.Question;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.objects.messages.*;
import com.vk.api.sdk.objects.users.responses.GetResponse;

/*
    // TODO: DEBUG
    List<GetResponse> users = config.vk.users()
            .get(config.actor)
            .userIds(String.valueOf(peerId))
            .execute();

    GetResponse u = users.getFirst();
    System.out.println(u.getFirstName() + " " + u.getLastName());
*/

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.tinty.Firebase.FirebaseHelper.*;

public class VKCallbackHandler {
    private final VKBotConfig config;
    private Set<Long> allowedToWriteUsers = ConcurrentHashMap.newKeySet();

    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();
    private final Map<Long, QuestionMessage> userMessages = new ConcurrentHashMap<>();
    private final Map<Long, String> userGroups = new ConcurrentHashMap<>();

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
            allowedToWriteUsers.remove(peerId);

            QuestionMessage currentQuestionMessage = userMessages.get(peerId);

            ArrayList<Answer> answers = currentQuestionMessage.getAnswers();

            Answer answer = new Answer(peerId, text, getName(peerId));
            answers.add(answer);

            Question newQuestion = new Question(currentQuestionMessage.getContent(), currentQuestionMessage.getType(),
                    currentQuestionMessage.getAnswers());

            updateQuestionByIndex(userGroups.get(peerId), currentQuestionMessage.getQuestions().indexOf(currentQuestionMessage),
                    newQuestion, new OnQuestionsUpdatedListener() {
                @Override
                public void onSuccess() {
                    // TODO: DEBUG
                    Keyboard kb = buildLobbyInlineKeyboard(userMessages.get(peerId).getState());

                    sendMessage(peerId, "Комментарий сохранен.\n\nТекущий вопрос:\n" + currentQuestionMessage.getContent(), kb);
                }

                @Override
                public void onFailure(String error) {
                    System.err.println(error);
                }
            });
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

                        userGroups.put(peerId, groupId);

                        // DEBUG: Добавляет участника в БД
                        addParticipant(groupId, peerId);

                        getGroupQuestions(groupId, new OnQuestionsRetrievedListener() {
                            @Override
                            public void onSuccess(List<Question> questions) {
                                Keyboard kb = buildLobbyInlineKeyboard(ItemState.FIRST);
                                Question firstQuestion = questions.getFirst();
                                QuestionMessage questionMessage = new QuestionMessage(
                                        firstQuestion.getContent(),
                                        firstQuestion.getType(),
                                        firstQuestion.getAnswers(),
                                        (ArrayList<Question>) questions,
                                        ItemState.FIRST
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
                    // Игнор или предупреждение
                    sendMessage(peerId, "Пожалуйста, сначала выберите действие.");
                }
            }
        }
        return "ok";
    }

    private String handleMessageEvent(JsonObject event) {
        long peerId = event.get("peer_id").getAsLong();
        JsonObject payload = event.get("payload").getAsJsonObject();
        String action = payload.get("action").getAsString();

        switch (action) {
            case "join_lobby" -> {
                sendMessage(peerId, "Введите код лобби:");

                userStates.put(peerId, UserState.WAITING_FOR_LOBBY_CODE);
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
                            prevQuestion.getAnswers(),
                            questions,
                            ItemState.NONE
                    ));
                } else {
                    kb = buildLobbyInlineKeyboard(ItemState.FIRST);
                    sendMessage(peerId, "Более ранних вопросов нет.", kb); // Не достижимо, но пусть будет

                    userMessages.replace(peerId, new QuestionMessage(
                            questionMessage.getContent(),
                            questionMessage.getType(),
                            questionMessage.getAnswers(),
                            questions,
                            ItemState.FIRST
                    ));
                }
            }
            case "allow_comment" -> {
                allowedToWriteUsers.add(peerId);

                sendMessage(peerId, "Теперь вы можете оставить комментарий:");
            }
            case "read_action" -> {
                ArrayList<Answer> answers;
                if (!(answers = userMessages.get(peerId).getAnswers()).isEmpty()) {
                    Keyboard kb = buildReadInlineKeyboard(ItemState.FIRST, 0);

                    Answer answer = answers.getFirst();
                    String message = String.format("%s %s\n\n%s",
                            answer.getAuthor(), answer.getTimeStamp(), answer.getText());
                    sendMessage(peerId, message, kb);
                } else {
                    sendMessage(peerId, "К этому вопросу пока нет комментариев.");
                }

            }
            case "move_back_read" -> {
                int answerIndex = payload.get("answer_index").getAsInt() - 1; // answer_index stores index of the next question
                ArrayList<Answer> answers = userMessages.get(peerId).getAnswers();

                Keyboard kb;
                if (answerIndex == 0) kb = buildReadInlineKeyboard(ItemState.FIRST, answerIndex);
                else kb = buildReadInlineKeyboard(ItemState.NONE, answerIndex);

                Answer answer = answers.get(answerIndex);

                String message = String.format("%s %s\n\n%s",
                        answer.getAuthor(), answer.getTimeStamp(), answer.getText());

                sendMessage(peerId, message, kb);
            }
            case "move_forward_read" -> {
                int answerIndex = payload.get("answer_index").getAsInt() + 1; // answer_index stores index of the prev question
                ArrayList<Answer> answers = userMessages.get(peerId).getAnswers();

                Keyboard kb;
                if (answerIndex == answers.size() - 1) kb = buildReadInlineKeyboard(ItemState.LAST, answerIndex);
                else kb = buildReadInlineKeyboard(ItemState.NONE, answerIndex);

                Answer answer = answers.get(answerIndex);

                String message = String.format("%s %s\n\n%s",
                        answer.getAuthor(), answer.getTimeStamp(), answer.getText());

                sendMessage(peerId, message, kb);
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
                            nextQuestion.getAnswers(),
                            questions,
                            ItemState.NONE
                    ));
                } else {
                    kb = buildLobbyInlineKeyboard(ItemState.LAST);
                    sendMessage(peerId, "Более поздних вопросов нет.\n\nТекущий вопрос:\n" + questions.get(questionIndex).getContent(), kb);
                    userMessages.replace(peerId, new QuestionMessage(
                            questionMessage.getContent(),
                            questionMessage.getType(),
                            questionMessage.getAnswers(),
                            questions,
                            ItemState.LAST
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

    private String getName(long peerId) {
        List<GetResponse> users;
        try {
            users = config.vk.users()
                    .get(config.actor)
                    .userIds(String.valueOf(peerId))
                    .execute();
        } catch (ApiException | ClientException e) {
            throw new RuntimeException(e);
        }

        GetResponse u = users.getFirst();
        return u.getFirstName() + " " + u.getLastName();
    }

    private Keyboard buildStartInlineKeyboard() {
        KeyboardButtonActionCallback joinAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"join_lobby\"}")
                .setLabel("Присоединиться");

        KeyboardButton joinButton = new KeyboardButton()
                .setAction(joinAction)
                .setColor(KeyboardButtonColor.PRIMARY);

        List<KeyboardButton> row1 = List.of(joinButton);
        List<List<KeyboardButton>> buttons = List.of(row1);

        return new Keyboard()
                .setInline(true)
                .setButtons(buttons);
    }

    private Keyboard buildLobbyInlineKeyboard() {
        KeyboardButton[] buttonRow = new KeyboardButton[4];

        KeyboardButtonActionCallback backAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"move_back\"}")
                .setLabel("⬅️");

        KeyboardButtonActionCallback commentAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"allow_comment\"}")
                .setLabel("\uD83D\uDCDD");

        KeyboardButtonActionCallback readAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"read_action\"}")
                .setLabel("\uD83D\uDCD6");

        KeyboardButtonActionCallback forwardAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"move_forward\"}")
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

    private Keyboard buildLobbyInlineKeyboard(ItemState state) {
        if (state == ItemState.NONE) return buildLobbyInlineKeyboard();

        KeyboardButton[] buttonRow = new KeyboardButton[3];

        if (state != ItemState.FIRST) {
            KeyboardButtonActionCallback backAction = new KeyboardButtonActionCallback()
                    .setType(KeyboardButtonActionCallbackType.CALLBACK)
                    .setPayload("{\"action\":\"move_back\"}")
                    .setLabel("⬅️");

            KeyboardButton backButton = new KeyboardButton()
                    .setAction(backAction)
                    .setColor(KeyboardButtonColor.DEFAULT);

            buttonRow[0] = backButton;
        }

        KeyboardButtonActionCallback commentAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"allow_comment\"}")
                .setLabel("\uD83D\uDCDD");

        KeyboardButtonActionCallback readAction = new KeyboardButtonActionCallback()
                .setType(KeyboardButtonActionCallbackType.CALLBACK)
                .setPayload("{\"action\":\"read_action\"}")
                .setLabel("\uD83D\uDCD6");

        if (state != ItemState.LAST) {
            KeyboardButtonActionCallback forwardAction = new KeyboardButtonActionCallback()
                    .setType(KeyboardButtonActionCallbackType.CALLBACK)
                    .setPayload("{\"action\":\"move_forward\"}")
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

        if (state == ItemState.FIRST) {
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

    public Keyboard buildReadInlineKeyboard(ItemState state, int answerIndex) {
        KeyboardButton[] buttonRow;
        switch (state) {
            case FIRST -> {
                buttonRow = new KeyboardButton[1];

                KeyboardButtonActionCallback forwardAction = new KeyboardButtonActionCallback()
                        .setType(KeyboardButtonActionCallbackType.CALLBACK)
                        .setPayload(String.format("{" +
                                "\"action\":\"move_forward_read\"," +
                                "\"answer_index\":%d" +
                                "}",
                                answerIndex))
                        .setLabel("➡️");

                KeyboardButton forwardButton = new KeyboardButton()
                        .setAction(forwardAction)
                        .setColor(KeyboardButtonColor.DEFAULT);

                buttonRow[0] = forwardButton;
            }
            case NONE -> {
                buttonRow = new KeyboardButton[2];

                KeyboardButtonActionCallback backAction = new KeyboardButtonActionCallback()
                        .setType(KeyboardButtonActionCallbackType.CALLBACK)
                        .setPayload(String.format("{" +
                                        "\"action\":\"move_back_read\"," +
                                        "\"answer_index\":%d" +
                                        "}",
                                answerIndex))
                        .setLabel("⬅️");

                KeyboardButton backButton = new KeyboardButton()
                        .setAction(backAction)
                        .setColor(KeyboardButtonColor.DEFAULT);

                KeyboardButtonActionCallback forwardAction = new KeyboardButtonActionCallback()
                        .setType(KeyboardButtonActionCallbackType.CALLBACK)
                        .setPayload(String.format("{" +
                                        "\"action\":\"move_forward_read\"," +
                                        "\"answer_index\":%d" +
                                        "}",
                                answerIndex))
                        .setLabel("➡️");

                KeyboardButton forwardButton = new KeyboardButton()
                        .setAction(forwardAction)
                        .setColor(KeyboardButtonColor.DEFAULT);

                buttonRow[0] = backButton;
                buttonRow[1] = forwardButton;
            }
            case LAST -> {
                buttonRow = new KeyboardButton[1];

                KeyboardButtonActionCallback backAction = new KeyboardButtonActionCallback()
                        .setType(KeyboardButtonActionCallbackType.CALLBACK)
                        .setPayload(String.format("{" +
                                        "\"action\":\"move_back_read\"," +
                                        "\"answer_index\":%d" +
                                        "}",
                                answerIndex))
                        .setLabel("⬅️");

                KeyboardButton backButton = new KeyboardButton()
                        .setAction(backAction)
                        .setColor(KeyboardButtonColor.DEFAULT);

                buttonRow[0] = backButton;
            }
            case null, default -> {
                System.err.println("buildReadInlineKeyboard: Некорректный ItemState ");
                return null;
            }
        }
        List<List<KeyboardButton>> buttons = List.of(List.of(buttonRow));

        return new Keyboard()
                .setInline(true)
                .setButtons(buttons);
    }
}
