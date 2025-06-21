package com.tinty.Firebase; // Предполагается, что это часть вашего существующего пакета

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.*;
import com.google.firebase.internal.NonNull;
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

import static com.tinty.Main.getUserId;

public class FirebaseHelper {
    public static final String GROUPS_KEY = "groups";
    public static final String PARTICIPANTS_KEY = "participants";

    // Пример инициализации Firebase (если она еще не сделана)
    // Обычно это делается один раз при запуске приложения
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

        if (FirebaseApp.getApps().isEmpty()) { // Проверяем, чтобы избежать повторной инициализации
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
        // Получаем ссылку на базу данных
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        // Получаем ссылку на нужный узел
        DatabaseReference ref = database.getReference(nodePath);

        // Используем CountDownLatch для ожидания асинхронного ответа от Firebase
        CountDownLatch latch = new CountDownLatch(1);
        final Map<String, Object>[] resultHolder = new Map[1];
        final Exception[] errorHolder = new Exception[1]; // Для хранения ошибок

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // GenericTypeIndicator используется для правильной десериализации в Map<String, Object>
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
            // Ожидаем завершения операции (с таймаутом, чтобы избежать бесконечного ожидания)
            if (!latch.await(10, TimeUnit.SECONDS)) { // Максимум 10 секунд ожидания
                System.err.println("Firebase read timed out.");
                return null;
            }
            if (errorHolder[0] != null) {
                // Если произошла ошибка при чтении из Firebase, выбрасываем ее
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

    public static void addParticipant(String groupId) {
        GroupParticipant newParticipant = new GroupParticipant(getUserId());
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
}