package com.tinty.Firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.*;
import com.google.firebase.database.core.SyncTree;
import com.google.firebase.database.core.view.Event;
import com.google.firebase.internal.NonNull;
import com.tinty.Firebase.Entity.Answer;
import com.tinty.Firebase.Entity.GroupParticipant;
import com.tinty.Firebase.Entity.Question;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FirebaseHelper {
    public static final String GROUPS_KEY = "groups";
    public static final String PARTICIPANTS_KEY = "participants";

    public static void initializeFirebase() throws IOException {
        Dotenv dotenv = Dotenv.configure()
                .directory("./Private")
                .load();
        final String serviceAccountKeyPath = dotenv.get("SERVICE_ACCOUNT_KEY_PATH");
        final String databaseUrl = dotenv.get("DATABASE_URL");

        FileInputStream serviceAccount = new FileInputStream(serviceAccountKeyPath);

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .setDatabaseUrl(databaseUrl)
                .build();

        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options);
            System.out.println("Firebase initialized successfully.");
        }
    }

    /**
     * Получает данные из указанного узла Firebase Realtime Database
     * и преобразует их в Map<String, Object>.
     *
     * @param nodePath Путь к узлу в базе данных (например, "users/someUser/settings").
     * @return Map<String, Object>, содержащая данные из узла, или null, если данных нет или произошла ошибка.
     */
    public static Map<String, Object> getMapFromNode(String nodePath) {
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference ref = database.getReference(nodePath);

        // Используем CountDownLatch для ожидания асинхронного ответа от Firebase
        CountDownLatch latch = new CountDownLatch(1);
        final Map<String, Object>[] resultHolder = new Map[1];
        final Exception[] errorHolder = new Exception[1];

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                GenericTypeIndicator<Map<String, Object>> genericTypeIndicator = new GenericTypeIndicator<>() {};
                Map<String, Object> map = dataSnapshot.getValue(genericTypeIndicator);
                resultHolder[0] = map;
                latch.countDown(); // Сообщаем, что данные получены
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                System.err.println("Firebase read failed: " + databaseError.getMessage());
                errorHolder[0] = databaseError.toException(); // Сохраняем ошибку
                latch.countDown(); // Сообщаем, что произошла ошибка
            }
        });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { // Максимум 10 секунд ожидания
                System.err.println("Firebase read timed out.");
                return null;
            }
            if (errorHolder[0] != null) {
                throw new RuntimeException("Error reading from Firebase: " + errorHolder[0].getMessage(), errorHolder[0]);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Восстанавливаем прерывание
            System.err.println("Firebase read interrupted: " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            return null;
        }

        return resultHolder[0];
    }

    /**
     * @param groupId The id of the group which user joins
     */

    public static void addParticipant(String groupId, long userId) {
        GroupParticipant newParticipant = new GroupParticipant(userId);
        DatabaseReference groupRef = FirebaseDatabase.getInstance().getReference(GROUPS_KEY)
                .child(groupId);

        groupRef.child(PARTICIPANTS_KEY).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData mutableData) {
                int nextIndex = 0;

                for (MutableData child : mutableData.getChildren()) {
                    try {
                        int currentIndex = Integer.parseInt(child.getKey());
                        if (currentIndex >= nextIndex) {
                            nextIndex = currentIndex + 1;
                        }
                    } catch (NumberFormatException e) {
                        // Игнорируем нечисловые ключи
                    }
                }

                // Добавляем новый элемент
                mutableData.child(String.valueOf(nextIndex)).setValue(newParticipant);

                return Transaction.success(mutableData);
            }

            @Override
            public void onComplete(DatabaseError databaseError, boolean committed, DataSnapshot dataSnapshot) {
                if (databaseError != null) {
                    System.out.println("Транзакция не удалась: " + databaseError.getMessage());
                }
            }
        });
    }

    /**
     * Интерфейс для callback'ов при получении вопросов
     */
    public interface OnQuestionsRetrievedListener {
        void onSuccess(List<Question> questions);
        void onFailure(String error);
    }

    /**
     * Получить List<Question> для конкретной группы
     * @param listener Callback для обработки результата
     */
    public static void getGroupQuestions(String groupId, OnQuestionsRetrievedListener listener) {
        DatabaseReference groupRef = FirebaseDatabase.getInstance().getReference(GROUPS_KEY)
                .child(groupId);
        groupRef.child("questions")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        try {
                            List<Question> questions = new ArrayList<>();

                            if (dataSnapshot.exists()) {
                                for (DataSnapshot questionSnapshot : dataSnapshot.getChildren()) {
                                    Question question = questionSnapshot.getValue(Question.class);
                                    if (question != null) {
                                        questions.add(question);
                                    }
                                }
                            }
                            listener.onSuccess(questions);

                        } catch (Exception e) {
                            e.printStackTrace();
                            listener.onFailure("Ошибка при парсинге данных: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        listener.onFailure("Ошибка базы данных: " + error.getMessage());
                    }
                });
    }

//    public interface OnQuestionRetrievedListener {
//        void onSuccess(Question question);
//        void onFailure(String error);
//    }
//
//    public static void getQuestionByIndex(String groupId, int questionIndex, OnQuestionRetrievedListener listener) {
//        getGroupQuestions(groupId, new OnQuestionsRetrievedListener() {
//            @Override
//            public void onSuccess(List<Question> questions) {
//                if (questionIndex >= questions.size() || questionIndex < 0) listener.onFailure("Некорректный индекс");
//                else listener.onSuccess(questions.get(questionIndex));
//            }
//
//            @Override
//            public void onFailure(String error) {
//                listener.onFailure(error);
//            }
//        });
//    }
//
//    public interface OnAnswerRetrievedListener {
//        void onSuccess(List<Answer> answers);
//        void onFailure(String error);
//    }
//
//    public static void getQuestionAnswers(String groupId, int questionIndex, OnAnswerRetrievedListener listener) {
//
//    }

    /**
     * Интерфейс для callback'ов при обновлении вопросов
     */
    public interface OnQuestionsUpdatedListener {
        void onSuccess();
        void onFailure(String error);
    }

    /**
     * Обновить ArrayList<Question> для конкретной группы
     * @param questions Обновленный список вопросов
     * @param listener Callback для обработки результата
     */
    public static void updateGroupQuestions(String groupId, ArrayList<Question> questions,
                                     OnQuestionsUpdatedListener listener) {
        DatabaseReference groupRef = FirebaseDatabase.getInstance().getReference(GROUPS_KEY)
                .child(groupId);
        groupRef.child("questions")
                .setValue(questions, (error, ref) -> {
                    if (error == null) {
//                        System.out.println("Successfully updated questions for group " + groupId);
                        listener.onSuccess();
                    } else {
                        System.out.println("Failed to update questions for group " + groupId);
                        listener.onFailure("Ошибка при обновлении: " + error.getMessage());
                    }
                });
    }

    /**
     * Обновить конкретный вопрос по индексу
     * @param questionIndex Индекс вопроса для обновления
     * @param updatedQuestion Обновленный вопрос
     * @param listener Callback для обработки результата
     */
    public static void updateQuestionByIndex(String groupId, int questionIndex, Question updatedQuestion,
                                      OnQuestionsUpdatedListener listener) {
        getGroupQuestions(groupId, new OnQuestionsRetrievedListener() {
            @Override
            public void onSuccess(List<Question> currentQuestions) {
                if (questionIndex >= 0 && questionIndex < currentQuestions.size()) {
                    currentQuestions.set(questionIndex, updatedQuestion);
                    updateGroupQuestions(groupId, (ArrayList<Question>) currentQuestions, listener);
                } else {
                    listener.onFailure("Некорректный индекс вопроса");
                }
            }

            @Override
            public void onFailure(String error) {
                listener.onFailure(error);
            }
        });
    }
}