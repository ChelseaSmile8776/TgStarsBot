package com.example.TgStarsBot.service;


import com.example.TgStarsBot.config.BotConfig;
import com.example.TgStarsBot.model.TaskLink;
import com.example.TgStarsBot.model.User;
import com.example.TgStarsBot.repository.TaskRepository;
import com.example.TgStarsBot.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.sql.Timestamp;
import java.util.*;



@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {
    private final java.util.concurrent.ConcurrentHashMap<Long, String> topupTarget = new java.util.concurrent.ConcurrentHashMap<>(); // "ad" или "regular"
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> topupAmount = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String STATE_WAIT_AMOUNT = "ожидание_суммы";
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.Set<Long>> rewardedChannels = new java.util.concurrent.ConcurrentHashMap<>();
    // антидубль для успешных платежей (чтобы не зачислять 2 раза)
    private final java.util.Set<String> paidGuard =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());


    private java.util.Set<Long> rewardedSet(long userId) {
        return rewardedChannels.computeIfAbsent(userId, k -> java.util.concurrent.ConcurrentHashMap.<Long>newKeySet());
    }

    private final java.util.Map<Long, Long> channelIdCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private com.example.TgStarsBot.repository.PaymentRepository paymentRepository;


    final BotConfig config;

    private final Map<Long, String> userStates = new HashMap<>();
    private final Map<Long, String> channelCache = new HashMap<>();
    private final Map<Long, TaskLink> pendingConfirmations = new HashMap<>();

    public TelegramBot(BotConfig config) {
        this.config = config;
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/start", "\uD83E\uDD16 Начать"));
        listOfCommands.add(new BotCommand("/profile", "\uD83D\uDE0E Профиль"));
        try {
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        }
        catch (TelegramApiException e) {
            log.error("ОШИБКА КОМАНДЫ : " + e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasPreCheckoutQuery()) {
            var q = update.getPreCheckoutQuery();
            try {
                execute(org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery.builder()
                        .preCheckoutQueryId(q.getId())
                        .ok(true)
                        .build());
            } catch (TelegramApiException e) {
                log.error("pre_checkout error: {}", e.getMessage());
            }
            return;
        }
        // === Stars: успешный платёж ===
        if (update.hasMessage() && update.getMessage().hasSuccessfulPayment()) {
            var sp = update.getMessage().getSuccessfulPayment();

            if ("XTR".equalsIgnoreCase(sp.getCurrency())) {
                String payload = sp.getInvoicePayload();
                try {
                    String[] parts = payload.split(":");
                    if (parts.length >= 3 && "AD_TOPUP".equals(parts[0])) {
                        long userId = Long.parseLong(parts[1]);

                        // Сумму стараемся взять из payload
                        int stars;
                        try {
                            stars = Integer.parseInt(parts[2]);
                        } catch (Exception ignored) {
                            int total = sp.getTotalAmount();      // иногда приходит *100
                            stars = total >= 100 ? total / 100 : total;
                        }

                        final int starsFinal = stars; // <-- добавь это
                        userRepository.findById(userId).ifPresent(u -> {
                            u.setAdsBalance(java.util.Optional.ofNullable(u.getAdsBalance()).orElse(0) + starsFinal);
                            userRepository.save(u);
                        });

                        sendMessage(userId, "✅ Зачислено " + stars + "⭐️ на рекламный баланс.");

                        // сохранить платёж в журнал
                        try {
                            com.example.TgStarsBot.model.Payment p = new com.example.TgStarsBot.model.Payment();
                            p.setChatId(userId);

                            // username берём из БД (если он там есть)
                            userRepository.findById(userId).ifPresent(u -> p.setUserName(u.getUserName()));

                            p.setTarget("ad");                    // из payload
                            p.setMethod("stars");                 // способ оплаты
                            p.setCurrency(sp.getCurrency());      // "XTR"
                            p.setAmountStars(stars);              // сумма в звёздах (не *100)
                            p.setTelegramPaymentChargeId(sp.getTelegramPaymentChargeId());
                            p.setProviderPaymentChargeId(sp.getProviderPaymentChargeId());
                            p.setPayload(sp.getInvoicePayload());
                            p.setCreatedAt(new java.sql.Timestamp(System.currentTimeMillis()));

                            paymentRepository.save(p);
                        } catch (Exception ex) {
                            log.error("payment log save error: {}", ex.getMessage());
                        }

                    }
                } catch (Exception e) {
                    log.error("successful_payment parse error: {}", e.getMessage());
                }
            }
            return; // больше ничего в этом апдейте обрабатывать не нужно
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            if (messageText.startsWith("/start")) {
                registerUser(update.getMessage());
                if (messageText.length() > 6) {
                    String code = messageText.substring(7).trim();
                    processReferralCode(chatId, code);
                }
                startCommandRecieved(chatId, update.getMessage().getChat().getUserName());
                suggestTaskCommandRecieved(chatId, update.getMessage().getChat().getUserName());
            }
            else if (messageText.equals("/profile")) {
                profileCommandRecieved(chatId, update.getMessage().getChat().getUserName());
            }
            else if (messageText.equals("/cancel")) {
                startCommandRecieved(chatId, update.getMessage().getChat().getUserName());
                suggestTaskCommandRecieved(chatId, update.getMessage().getChat().getUserName());
                resetTopupState(chatId);
            }
            else if (messageText.equals("⭐️ Заработать Звёзды")) {
                startCommandRecieved(chatId, update.getMessage().getChat().getUserName());
                suggestTaskCommandRecieved(chatId, update.getMessage().getChat().getUserName());
            }
            else if (messageText.equals("💎 Задания")) {
                taskCommandRecieved(chatId, update.getMessage().getChat().getUserName());
            }
            else if (messageText.equals("🎁 Вывести Звёзды")) {
                withdrawCommandRecieved(chatId, update.getMessage().getChat().getUserName());
            }
            else if (messageText.equals("👥 Купить Подписчиков")) {
                profileCommandRecieved(chatId, update.getMessage().getChat().getUserName());
            }
            else if (messageText.equals("💸 Дёшево Купить Звёзды")) {
                buyStarsCommandRecieved(chatId, update.getMessage().getChat().getUserName());
            }
        } else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            long messageId = update.getCallbackQuery().getMessage().getMessageId();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            if (callbackData.equals("TASK_BUTTON")) {
                taskCommandRecieved(chatId, update.getCallbackQuery().getFrom().getUserName());
            }
            else if (callbackData.equals("MY_TASKS_BUTTON")) {
                chatId = update.getCallbackQuery().getMessage().getChatId();
                messageId = update.getCallbackQuery().getMessage().getMessageId();
                String creatorUsername = update.getCallbackQuery().getFrom().getUserName(); // username автора задания

                java.util.List<TaskLink> mine = new java.util.ArrayList<>();
                taskRepository.findAll().forEach(t -> {
                    String u = t.getUserName();
                    if (creatorUsername == null) {
                        // если у автора нет username, считаем совпадением только пустые userName в задачах
                        if (u == null || u.isBlank()) mine.add(t);
                    } else {
                        if (creatorUsername.equalsIgnoreCase(u)) mine.add(t);
                    }
                });

                StringBuilder sb = new StringBuilder();
                if (mine.isEmpty()) {
                    sb.append("\uD83D\uDCCB Мои задания\n" +
                            "\n" +
                            "В этом разделе вы можете управлять вашими заданиями.\n" +
                            "\n" +
                            "Условные обозначения:\n" +
                            "✅ - Завершено\n" +
                            "▶\uFE0F - В процессе\n" +
                            "⏳ - Осталось\n" +
                            "⛔\uFE0F - Отменено\n" +
                            "\n" +
                            "Для выбора задания, воспользуйтесь кнопками:");
                } else {
                    sb.append("📋 Ваши задания:\n\n");
                    int i = 1;
                    for (TaskLink t : mine) {
                        Long target = t.getTargetSubs();
                        Long current = 0L;
                        try {
                            java.lang.reflect.Method getCur = TaskLink.class.getMethod("getCurrentSubs");
                            Object curVal = getCur.invoke(t);
                            if (curVal instanceof Long) current = (Long) curVal;
                        } catch (Exception ignored) {}
                        long total = target != null ? target : 0L;
                        long left = total > 0 ? Math.max(total - (current != null ? current : 0L), 0L) : 0L;

                        sb.append(i++).append(") ").append(t.getLink() != null ? t.getLink() : "—").append("\n")
                                .append("   ▶\uFE0F - В процессе: ").append(current).append("/").append(total).append("\n");
                        if (total > 0) {
                            sb.append("  ⏳ - Осталось: ").append(left).append("\n");
                        }
                    }
                    sb.append("\n" + "Для выбора задания, воспользуйтесь кнопками:");
                }
                org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText msg = new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
                msg.setChatId(String.valueOf(chatId));
                msg.setMessageId((int) messageId);
                msg.setText(sb.toString());
                try {
                    execute(msg);
                } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
                    log.error("Callback ОШИБКА: MY_TASKS_BUTTON ---> " + e.getMessage());
                }
                org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup kb = new org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup();
                java.util.List<java.util.List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>> rows = new java.util.ArrayList<>();
                int i = 1;
                for (TaskLink t : mine) {
                    org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton cancelBtn =
                            new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton();
                    cancelBtn.setText("❌ Отменить №" + i++);
                    cancelBtn.setCallbackData("MYTASK_CANCEL:" + t.getChatId());
                    java.util.List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton> row = new java.util.ArrayList<>();
                    row.add(cancelBtn);
                    rows.add(row);
                }
// Кнопка "Назад"
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton backBtn = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton();
                backBtn.setText("⬅️ Назад");
                backBtn.setCallbackData("MY_TASKS_BACK");
                rows.add(java.util.List.of(backBtn));
                kb.setKeyboard(rows);

                msg = new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
                msg.setChatId(String.valueOf(chatId));
                msg.setMessageId((int) messageId);
                msg.setText(sb.toString());
                msg.setReplyMarkup(kb);
                try {
                    execute(msg);
                } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
                    log.error("Callback ОШИБКА: MY_TASKS_BUTTON ---> " + e.getMessage());
                }
            }
            else if (callbackData.startsWith("MYTASK_CANCEL:")) {
                String[] parts = callbackData.split(":", 2);
                Long taskId;
                try { taskId = Long.parseLong(parts[1]); } catch (Exception ex) { return; }

                var opt = taskRepository.findById(taskId);
                if (opt.isEmpty()) {
                    EditMessageText em = new EditMessageText();
                    em.setChatId(String.valueOf(chatId));
                    em.setMessageId((int) messageId);
                    em.setText("Это задание уже отсутствует.");
                    try { execute(em); } catch (TelegramApiException ignored) {}
                    return;
                }
                TaskLink t = opt.get();
                String creatorUsername = update.getCallbackQuery().getFrom().getUserName();
                String owner = t.getUserName();
                boolean allowed = (creatorUsername == null || creatorUsername.isBlank())
                        ? (owner == null || owner.isBlank())
                        : (owner != null && creatorUsername.equalsIgnoreCase(owner));
                if (!allowed) {
                    try {
                        execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                                .callbackQueryId(update.getCallbackQuery().getId())
                                .text("Вы не являетесь создателем этого задания.")
                                .showAlert(true).build());
                    } catch (TelegramApiException ignored) {}
                    return;
                }

                long target = t.getTargetSubs() != null ? t.getTargetSubs() : 0L;
                long current = safeCurrent(t); // твой хелпер
                long refund = Math.max(target - current, 0L);
                if (refund <= 0) {
                    taskRepository.delete(t);
                    EditMessageText em = new EditMessageText();
                    em.setChatId(String.valueOf(chatId));
                    em.setMessageId((int) messageId);
                    em.setText("Задание отменено. Возвратов нет.");
                    try { execute(em); } catch (TelegramApiException ignored) {}
                    return;
                }

                InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();

                InlineKeyboardButton toAd = new InlineKeyboardButton();
                toAd.setText("↩️ " + refund + "⭐️ на Рекламный");
                toAd.setCallbackData("MYTASK_REFUND_AD:" + taskId + ":" + refund);

                InlineKeyboardButton toBal = new InlineKeyboardButton();
                toBal.setText("↩️ " + refund + "⭐️ на Обычный");
                toBal.setCallbackData("MYTASK_REFUND_BAL:" + taskId + ":" + refund);

                InlineKeyboardButton backBtn = new InlineKeyboardButton();
                backBtn.setText("⬅\uFE0F Назад");
                backBtn.setCallbackData("MY_TASKS_BACK");

                rows.add(List.of(toAd));
                rows.add(List.of(toBal));
                rows.add(List.of(backBtn));
                kb.setKeyboard(rows);

                EditMessageText em = new EditMessageText();
                em.setChatId(String.valueOf(chatId));
                em.setMessageId((int) messageId);
                em.setText("Отменить задание?\nОстаток к возврату: " + refund + "⭐️\nВыберите, куда вернуть:");
                em.setReplyMarkup(kb);

                try { execute(em); } catch (TelegramApiException ignored) {}
            }
            else if (callbackData.startsWith("MYTASK_REFUND_AD:") || callbackData.startsWith("MYTASK_REFUND_BAL:")) {
                String[] parts = callbackData.split(":");
                if (parts.length < 3) return;

                Long taskId;
                long refund;
                try {
                    taskId = Long.parseLong(parts[1]);
                    refund = Long.parseLong(parts[2]);
                } catch (Exception ex) { return; }
                var opt = taskRepository.findById(taskId);
                if (opt.isEmpty()) {
                    EditMessageText em = new EditMessageText();
                    em.setChatId(String.valueOf(chatId));
                    em.setMessageId((int) messageId);
                    em.setText("Задание уже было удалено ранее.");
                    try { execute(em); } catch (TelegramApiException ignored) {}
                    return;
                }
                User user = userRepository.findById(chatId).orElse(null);
                if (user == null) return;

                if (callbackData.startsWith("MYTASK_REFUND_AD:")) {
                    user.setAdsBalance(java.util.Optional.ofNullable(user.getAdsBalance()).orElse(0) + (int) refund);
                } else {
                    user.setBalance(java.util.Optional.ofNullable(user.getBalance()).orElse(0) + (int) refund);
                }
                userRepository.save(user);
                taskRepository.delete(opt.get());
                try {
                    if (String.valueOf(taskId).equals(user.getActiveTask())) {
                        user.setActiveTask(null);
                        userRepository.save(user);
                    }
                } catch (Exception ignored) {}
                EditMessageText em = new EditMessageText();
                em.setChatId(String.valueOf(chatId));
                em.setMessageId((int) messageId);
                em.setText("Задание отменено. Возвращено " + refund + "⭐️ на " +
                        (callbackData.startsWith("MYTASK_REFUND_AD:") ? "рекламный" : "обычный") + " баланс.");
                try { execute(em); } catch (TelegramApiException ignored) {}
            }
            else if (callbackData.equals("CREATE_TASK_BUTTON")) {
                String text = "\uD83D\uDC8E Создать Задание \n" +
                        "\n" +
                        "1. Назначьте бота администратором канала\n" +
                        "2. Выдайте право: «добавление подписчиков»\n" +
                        "3. Перешлите сюда любой пост из канала\n" +
                        "\n" +
                        "❗\uFE0FОбратите внимание: пересылаемый пост должен быть опубликован от лица канала\n" +
                        "\n" +
                        "Запрещенные тематики:\n" +
                        "SCAM в любом виде, Любые чаты, Раздачи Звёзд, Конкурирующие тематики, NSFW, Стендофф, Робуксы\n" +
                        "\n" +
                        "Отменить — /cancel";
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setText(text);
                message.setMessageId((int) messageId);
                userStates.put(chatId, "ожидание_поста");
                try {
                    execute(message);
                }
                catch (TelegramApiException e) {
                    log.error("Callback ОШИБКА: CREATE_TASK_BUTTON ---> " + e.getMessage());
                }
            }
            else if (callbackData.equals("ADS_BALANCE_BUTTON")) {
                startTopupFlow(chatId, update.getCallbackQuery().getFrom().getUserName(), "ad");
            }
            else if (callbackData.equals("GO_TO_CHANNEL_BUTTON")) {
                String text = "test переход по ссылке";
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setText(text);
                message.setMessageId((int) messageId);
                try {
                    execute(message);
                }
                catch (TelegramApiException e) {
                    log.error("Callback ОШИБКА: GO_TO_CHANNEL_BUTTON ---> " + e.getMessage());
                }
            }
            else if (callbackData.equals("DONE_BUTTON")) {
                chatId = update.getCallbackQuery().getMessage().getChatId();
                messageId = update.getCallbackQuery().getMessage().getMessageId();
                long userId = update.getCallbackQuery().getFrom().getId(); // telegram user id

                TaskLink task = userRepository.findById(chatId).flatMap(u -> {
                    try { return taskRepository.findById(Long.parseLong(u.getActiveTask())); }
                    catch (Exception e) { return java.util.Optional.empty(); }
                }).orElse(null);

                if (task == null) {
                    // активного задания нет — просим зайти в задания заново
                    try {
                        execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                                .callbackQueryId(update.getCallbackQuery().getId())
                                .text("Нет активного задания. Открой «💎 Задания».")
                                .showAlert(false)
                                .build());
                    } catch (TelegramApiException ignored) {}
                    EditMessageText m = new EditMessageText();
                    m.setChatId(String.valueOf(chatId));
                    m.setMessageId((int) messageId);
                    m.setText("Не найдено активное задание. Нажмите «💎 Задания» ещё раз.");
                    try { execute(m); } catch (TelegramApiException ignored) {}
                    return;
                }
                // антидубль на уровне activeTask: награждаем ТОЛЬКО если activeTask == это задание
                Optional<User> uOpt = userRepository.findById(chatId);
                if (uOpt.isEmpty()) return;
                User u = uOpt.get();

                String expectedActive = String.valueOf(task.getChatId());
                if (!expectedActive.equals(u.getActiveTask())) {
                    // уже обработано (или пользователь успел нажать второй раз)
                    try {
                        execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                                .callbackQueryId(update.getCallbackQuery().getId())
                                .text("Это задание уже обработано.")
                                .showAlert(false)
                                .build());
                    } catch (TelegramApiException ignored) {}
                    EditMessageText m = new EditMessageText();
                    m.setChatId(String.valueOf(chatId));
                    m.setMessageId((int) messageId);
                    m.setText("⏱️ Задание уже обработано.");
                    try { execute(m); } catch (TelegramApiException ignored) {}
                    return;
                }
                if (rewardedSet(chatId).contains(task.getChatId())) {
                    try {
                        execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                                .callbackQueryId(update.getCallbackQuery().getId())
                                .text("Вы уже получали награду за этот канал.")
                                .showAlert(false)
                                .build());
                    } catch (TelegramApiException ignored) {}
                    EditMessageText m2 = new EditMessageText();
                    m2.setChatId(String.valueOf(chatId));
                    m2.setMessageId((int) messageId);
                    m2.setText("⏱️ Награда за этот канал уже была начислена ранее.");
                    try { execute(m2); } catch (TelegramApiException ignored) {}
                    return;
                }
                boolean subscribed = false;
                try {
                    var member = execute(new org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember(String.valueOf(task.getChatId()), userId));
                    String status = member.getStatus();
                    subscribed = "member".equals(status) || "administrator".equals(status) || "creator".equals(status);
                } catch (TelegramApiException e) {
                    log.warn("GetChatMember по chatId={} для userId={} не сработал: {}",
                            task.getChatId(), userId, e.getMessage());

                    if (task.getUserName() != null && !task.getUserName().isBlank()) {
                        try {
                            var member2 = execute(new org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember("@" + task.getUserName().replaceFirst("^@", ""), userId));
                            String status2 = member2.getStatus();
                            subscribed = "member".equals(status2) || "administrator".equals(status2) || "creator".equals(status2);
                        } catch (TelegramApiException e2) {
                            log.warn("GetChatMember по username=@{} для userId={} не сработал: {}",
                                    task.getUserName(), userId, e2.getMessage());
                        }
                    }
                }
                if (!subscribed) {
                    // НЕ ПОДПИСАН — сказать пользователю и НЕ продолжать
                    try {
                        execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                                .callbackQueryId(update.getCallbackQuery().getId())
                                .text("❌ Подписка не подтверждена. Подпишись и жми «Подтвердить».")
                                .showAlert(false)
                                .build());
                        EditMessageText m = new EditMessageText();
                        m.setChatId(String.valueOf(chatId));
                        m.setMessageId((int) messageId);
                        m.setText("❌ Не удалось подтвердить подписку. Подпишитесь и нажмите «Подтвердить» ещё раз.");
                        execute(m);
                    } catch (TelegramApiException ignored) {}
                    return;
                }
                // 4) подписка подтверждена — начисляем ОДИН РАЗ и закрываем активку
                u.setBalance(java.util.Optional.ofNullable(u.getBalance()).orElse(0) + 1);
                u.setActiveTask(null); // больше нельзя подтвердить это же задание повторно
                userRepository.save(u);

                rewardedSet(chatId).add(task.getChatId());
                // уменьшаем цель/увеличиваем прогресс и удаляем запись, если цель достигнута
                try {
                    java.lang.reflect.Method getCur = null, setCur = null;
                    try {
                        getCur = TaskLink.class.getMethod("getCurrentSubs");
                        setCur = TaskLink.class.getMethod("setCurrentSubs", Long.class);
                    } catch (NoSuchMethodException ignored) {}
                    if (getCur != null && setCur != null) {
                        Long current = (Long) getCur.invoke(task);
                        long newCur = (current == null ? 0L : current) + 1L;
                        setCur.invoke(task, newCur);
                        Long target = task.getTargetSubs() == null ? 0L : task.getTargetSubs();
                        if (newCur >= target && target > 0L) {
                            taskRepository.delete(task);
                        } else {
                            taskRepository.save(task);
                        }
                    } else {
                        long left = java.util.Optional.ofNullable(task.getTargetSubs()).orElse(0L) - 1L;
                        if (left <= 0) {
                            taskRepository.delete(task);
                        } else {
                            task.setTargetSubs(left);
                            taskRepository.save(task);
                        }
                    }
                } catch (Exception e) {
                    // на всякий случай просто сохранить без падения
                    try { taskRepository.save(task); } catch (Exception ignored) {}
                }
                try {
                    execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                            .callbackQueryId(update.getCallbackQuery().getId())
                            .text("✅ Подтверждено! Начислено +1⭐️")
                            .showAlert(false)
                            .build());
                    EditMessageText m = new EditMessageText();
                    m.setChatId(String.valueOf(chatId));
                    m.setMessageId((int) messageId);
                    m.setText("✅ Подписка подтверждена! Начислено +1⭐️\n\n" +
                            " ❗\uFE0FНе отписывайтесь от канала в течение как минимум 7 дней. В противном случае, вы получите штраф или блокировку аккаунта.");
                    execute(m);
                } catch (TelegramApiException ignored) {}
                // показать СЛЕДУЮЩЕЕ задание
                taskCommandRecieved(chatId, update.getCallbackQuery().getFrom().getUserName());
            }
            else if (callbackData.equals("SKIP_TASK_BUTTON")) {
                // сбрасываем активное задание, чтобы не застревать на старом
                userRepository.findById(chatId).ifPresent(u -> {
                    u.setActiveTask(null);
                    userRepository.save(u);
                });
                try {
                    EditMessageText m = new EditMessageText();
                    m.setChatId(String.valueOf(chatId));
                    m.setMessageId((int) messageId);
                    m.setText("⏩ Показал другое задание");
                    execute(m);
                } catch (TelegramApiException ignored) {}
                taskCommandRecieved(chatId, update.getCallbackQuery().getFrom().getUserName());
            }
            else if (callbackData.equals("MY_TASKS_BACK")) {
                messageId = update.getCallbackQuery().getMessage().getMessageId();

                org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage del = new org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage();
                del.setChatId(String.valueOf(chatId));
                del.setMessageId((int) messageId);
                try {
                    execute(del);
                } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
                    log.error("Callback ОШИБКА: MY_TASKS_BACK (delete) ---> " + e.getMessage());
                }
                profileCommandRecieved(chatId, update.getCallbackQuery().getFrom().getUserName());
            }
            else if (callbackData.equals("25_STARS")) {
                String text = "test 25";
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setText(text);
                message.setMessageId((int) messageId);
                try {
                    execute(message);
                }
                catch (TelegramApiException e) {
                    log.error("Callback ОШИБКА: 25_STARS ---> " + e.getMessage());
                }
            }
            else if (callbackData.equals("50_STARS")) {
                String text = "test 50";
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setText(text);
                message.setMessageId((int) messageId);
                try {
                    execute(message);
                }
                catch (TelegramApiException e) {
                    log.error("Callback ОШИБКА: 50_STARS ---> " + e.getMessage());
                }
            }
            else if (callbackData.equals("100_STARS")) {
                String text = "test 100";
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setText(text);
                message.setMessageId((int) messageId);
                try {
                    execute(message);
                }
                catch (TelegramApiException e) {
                    log.error("Callback ОШИБКА: 100_STARS ---> " + e.getMessage());
                }
            }
            else if (callbackData.equals("200_STARS")) {
                String text = "test 200";
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setText(text);
                message.setMessageId((int) messageId);
                try {
                    execute(message);
                }
                catch (TelegramApiException e) {
                    log.error("Callback ОШИБКА: 200_STARS ---> " + e.getMessage());
                }
            }
            if ("CONFIRM_TASK".equals(callbackData)) {
                TaskLink task = pendingConfirmations.get(chatId);
                if (task != null) {
                    User user = userRepository.findById(chatId).orElseThrow();
                    if (user.getAdsBalance() >= task.getTargetSubs()) {
                        user.setAdsBalance((int) (user.getAdsBalance() - task.getTargetSubs()));
                        userRepository.save(user);
                        taskRepository.save(task);
                        EditMessageText message = new EditMessageText();
                        message.setChatId(String.valueOf(chatId));
                        message.setMessageId(update.getCallbackQuery().getMessage().getMessageId());
                        message.setText("✅ Задание успешно создано и оплачено!");
                        try {
                            execute(message);
                        }
                        catch (TelegramApiException e) {
                            log.error("Callback ОШИБКА: 100_STARS ---> " + e.getMessage());
                        }
                    } else {
                        sendMessage(chatId, "❌ Недостаточно звёзд на балансе!");
                    }
                }
                pendingConfirmations.remove(chatId);
            }
            else if ("CANCEL_TASK".equals(callbackData)) {
                pendingConfirmations.remove(chatId);
                EditMessageText message = new EditMessageText();
                message.setChatId(String.valueOf(chatId));
                message.setMessageId(update.getCallbackQuery().getMessage().getMessageId());
                message.setText("❌ Создание задания отменено");
                try {
                    execute(message);
                }
                catch (TelegramApiException e) {
                    log.error("Callback ОШИБКА: 100_STARS ---> " + e.getMessage());
                }
            }
            // 1) Реальный перевод с обычного баланса → рекламный
            else if (callbackData.equals("TOPUP_FROM_BALANCE")) {
                String target = topupTarget.getOrDefault(chatId, "regular"); // "ad" или "regular"
                Integer amount = topupAmount.get(chatId);

                // Проверка, что сумма есть
                if (amount == null || amount <= 0) {
                    try {
                        execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                                .callbackQueryId(update.getCallbackQuery().getId())
                                .text("Сначала введите сумму.")
                                .showAlert(false)
                                .build());
                    } catch (TelegramApiException ignored) {}
                    return;
                }

                // Перевод с обычного баланса имеет смысл ТОЛЬКО для пополнения рекламного
                if (!"ad".equals(target)) {
                    try {
                        execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                                .callbackQueryId(update.getCallbackQuery().getId())
                                .text("Этот способ доступен только для пополнения рекламного баланса.")
                                .showAlert(false)
                                .build());
                    } catch (TelegramApiException ignored) {}
                    return;
                }

                User user = userRepository.findById(chatId).orElse(null);
                if (user == null) return;

                int regular = java.util.Optional.ofNullable(user.getBalance()).orElse(0);
                int ad = java.util.Optional.ofNullable(user.getAdsBalance()).orElse(0);

                if (regular < amount) {
                    // Тихое уведомление + редактирование текущего сообщения (без новых сообщений в чат)
                    try {
                        execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                                .callbackQueryId(update.getCallbackQuery().getId())
                                .text("Недостаточно ⭐ на обычном балансе.")
                                .showAlert(false)
                                .build());
                    } catch (TelegramApiException ignored) {}

                    org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText em =
                            new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
                    em.setChatId(String.valueOf(chatId));
                    em.setMessageId((int) messageId);
                    em.setText("❌ Недостаточно ⭐ на обычном балансе.\n" +
                            "Баланс: " + regular + "⭐️\n" +
                            "Сумма: " + amount + "⭐️\n\n" +
                            "Введите меньшую сумму или отмените — /cancel");
                    try { execute(em); } catch (TelegramApiException ignored) {}
                    return;
                }

                // Выполняем перевод
                user.setBalance(regular - amount);
                user.setAdsBalance(ad + amount);
                userRepository.save(user);

                // Чистим временное состояние
                topupAmount.remove(chatId);
                topupTarget.remove(chatId);
                userStates.remove(chatId);

                // Редактируем текущее сообщение — подтверждение
                org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText em =
                        new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
                em.setChatId(String.valueOf(chatId));
                em.setMessageId((int) messageId);
                em.setText("✅ Успешно!\n" +
                        "Переведено: " + amount + "⭐️ с обычного на рекламный.\n\n" +
                        "Обычный: " + user.getBalance() + "⭐️\n" +
                        "Рекламный: " + user.getAdsBalance() + "⭐️");
                try { execute(em); } catch (TelegramApiException ignored) {}
            }
            else if (callbackData.equals("TOPUP_FROM_BALANCE")
                    || callbackData.equals("TOPUP_TGSTARS")
                    || callbackData.equals("TOPUP_SBP")
                    || callbackData.equals("TOPUP_CRYPTO")
                    || callbackData.equals("TOPUP_TON")) {

                if ("TOPUP_TGSTARS".equals(callbackData)) {
                    Integer amount = topupAmount.get(chatId);
                    if (amount == null || amount < 50) {
                        sendMessage(chatId, "Минимум: 50⭐️. Введите сумму снова.\n\nОтменить — /cancel");
                        return;
                    }
                    String payload = "AD_TOPUP:" + chatId + ":" + amount;
                    sendStarsInvoiceRaw(chatId, amount, payload, "Пополнение рекламного баланса",
                            "Пополнение на " + amount + "⭐️ через Telegram Stars");
                    return;
                }

                String target = topupTarget.getOrDefault(chatId, "regular"); // "ad" или "regular"
                Integer amount = topupAmount.get(chatId);
                if (amount == null) amount = 0;

                String methodName =
                        "TOPUP_FROM_BALANCE".equals(callbackData) ? "С обычного баланса" :
                                "TOPUP_TGSTARS".equals(callbackData)     ? "Telegram Stars" :
                                        "TOPUP_SBP".equals(callbackData)         ? "Рубли (СБП)" :
                                                "TOPUP_TON".equals(callbackData)         ? "TON" :
                                                        "Криптовалюты";


                String title = "💳 Пополнение рекламного баланса";
                if ("regular".equals(target)) title = "💸 Покупка Звёзд";

                String summary = title + "\n\n" +
                        "Способ: " + methodName + "\n" +
                        "Сумма: " + amount + "⭐️\n\n" +
                        "Платёжный шлюз пока не подключен.\n" +
                        "Отменить — /cancel";

                org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText em =
                        new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
                em.setChatId(String.valueOf(chatId));
                em.setMessageId((int) messageId);
                em.setText(summary);

                try { execute(em); } catch (TelegramApiException ignored) {}

                // Чистим временное состояние
                topupAmount.remove(chatId);
                topupTarget.remove(chatId);
                userStates.remove(chatId);
            }
        }
        if (update.hasMessage() && update.getMessage().getForwardFromChat() != null) {
            long chatId = update.getMessage().getFrom().getId();
            if ("ожидание_поста".equals(userStates.get(chatId))) {
                Chat channel = update.getMessage().getForwardFromChat();
                channelIdCache.put(chatId, channel.getId());
                String username = channel.getUserName(); // НЕ title!
                if (username == null || username.isBlank()) {
                    sendMessage(chatId, "❗️У этого канала нет @username. Для приватных каналов нужна invite-ссылка t.me/… или права бота на создание инвайта.");
                    return;
                }
                String link = "https://t.me/" + username.replaceFirst("^@", "");

                channelCache.put(chatId, link);
                channelCache.put(chatId, username.replaceFirst("^@", ""));
                channelIdCache.put(chatId, channel.getId());

                userStates.put(chatId, "ожидание_числа");
                sendMessage(chatId, "Введите нужное число подписчиков\n\nМинимум: 200\n\nЦена за подписчика: 1⭐️\n\nОтменить — /cancel");
                return;
            }
        }
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText();

            // быстрый перехват ввода суммы для сценария пополнения/покупки
            if (STATE_WAIT_AMOUNT.equals(userStates.get(chatId))) {
                // если пользователь прислал чисто число — обрабатываем сумму здесь и выходим
                if (text.trim().matches("\\d+")) {
                    int amount = Integer.parseInt(text.trim());

                    if (amount < 50) {
                        sendMessage(chatId,
                                "❗️ Минимальная сумма — 50⭐️\n\n" +
                                        "Введите сумму снова.\n\n" +
                                        "Отменить — /cancel");
                        return;
                    }
                    topupAmount.put(chatId, amount);

                    String title = "💳 Выберите способ пополнения:";
                    String target = topupTarget.getOrDefault(chatId, "regular");
                    if ("regular".equals(target)) {
                        title = "💸 Выберите способ покупки:";
                    }

                    SendMessage msg = new SendMessage();
                    msg.setChatId(String.valueOf(chatId));
                    msg.setText(title + "\n\n" +
                            "Сумма: " + amount + "⭐️\n\n" +
                            "Отменить — /cancel");
                    msg.setReplyMarkup(buildTopupMethodsKb(target));

                    try { execute(msg); }
                    catch (TelegramApiException e) { log.error("wait amount -> methods error: " + e.getMessage()); }
                    return; // важно: дальше не идём
                }
            }
            if ("ожидание_числа".equals(userStates.get(chatId))) {
                Chat channel = update.getMessage().getForwardFromChat();
                try {
                    long subsCount = Long.parseLong(text);
                    if (subsCount < 200) {
                        sendMessage(chatId, "Введите нужное число подписчиков\n\nМинимум: 200\n\nЦена за подписчика: 1⭐️\n\nОтменить — /cancel");
                        return;
                    }

                    User user = userRepository.findById(chatId).orElseThrow();
                    if (user.getAdsBalance() < subsCount) {
                        sendMessage(chatId, "❌ Недостаточно звёзд на рекламном балансе!\nВаш баланс: " + user.getAdsBalance() + "⭐️");
                        return;
                    }
                    Long channelIdForTask = channelIdCache.get(chatId);
                    String link = channelCache.get(chatId);

                    if (channelIdForTask == null || link == null || link.isBlank()) {
                        sendMessage(chatId, "⚠️ Не удалось определить канал. Начните заново: «Создать задание».");
                        return;
                    }
                    TaskLink task = new TaskLink();
                    task.setChatId(channelIdCache.get(chatId));
                    task.setUserName(user.getUserName());
                    task.setLink(link);
                    task.setTargetSubs(subsCount);
                    task.setCurrentSubs(0L);

                    String messageText = String.format("📝 Внимательно проверьте задание:\n\nКанал: %s\nЧисло подписчиков: %d\n\nК оплате: %d⭐️", link, subsCount, subsCount);

                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                    InlineKeyboardButton confirmBtn = new InlineKeyboardButton();
                    confirmBtn.setText("✅ Подтвердить");
                    confirmBtn.setCallbackData("CONFIRM_TASK");
                    InlineKeyboardButton cancelBtn = new InlineKeyboardButton();
                    cancelBtn.setText("❌ Отменить");
                    cancelBtn.setCallbackData("CANCEL_TASK");
                    java.util.List<java.util.List<InlineKeyboardButton>> keyboard = new java.util.ArrayList<>();
                    keyboard.add(java.util.Collections.singletonList(confirmBtn));
                    keyboard.add(java.util.Collections.singletonList(cancelBtn));
                    markup.setKeyboard(keyboard);

                    SendMessage confirmMsg = new SendMessage();
                    confirmMsg.setChatId(String.valueOf(chatId));
                    confirmMsg.setText(messageText);
                    confirmMsg.setReplyMarkup(markup);
                    try {
                        execute(confirmMsg);
                        pendingConfirmations.put(chatId, task);
                        userStates.remove(chatId);
                        channelCache.remove(chatId);
                        channelIdCache.remove(chatId); // не забываем очистить id
                    } catch (TelegramApiException e) {
                        log.error("Ошибка отправки: " + e.getMessage());
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "❌ Введите число!");
                } catch (Exception e) {
                    log.error("Ошибка: " + e.getMessage());
                    sendMessage(chatId, "⚠️ Произошла ошибка, попробуйте позже");
                }
            }
        }
    }

    private boolean sendStarsInvoiceRaw(long chatId, int starsAmount, String payload, String title, String description) {
        java.net.HttpURLConnection conn = null;
        try {
            // Если увидишь PRICE_TOTAL_AMOUNT_INVALID — замени на: int units = starsAmount * 100;
            int units = starsAmount;

            String url = "https://api.telegram.org/bot" + getBotToken() + "/sendInvoice";
            String json = "{"
                    + "\"chat_id\":\"" + chatId + "\","
                    + "\"title\":" + toJson(title) + ","
                    + "\"description\":" + toJson(description) + ","
                    + "\"payload\":" + toJson(payload) + ","
                    + "\"currency\":\"XTR\","
                    + "\"start_parameter\":\"ad_topup_" + starsAmount + "\","
                    + "\"prices\":[{\"label\":\"Stars\",\"amount\":" + units + "}]"
                    + "}";

            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            if (code >= 200 && code < 300) {
                log.info("Stars invoice OK: {}", resp);
                return true;
            } else {
                log.error("Stars invoice FAIL [{}]: {}", code, resp);
                String human = "Не удалось создать счёт. Код: " + code;
                try {
                    int i = resp.indexOf("\"description\":\"");
                    if (i >= 0) {
                        int j = resp.indexOf('"', i + 15);
                        if (j > i) human += "\n" + resp.substring(i + 15, j);
                    }
                } catch (Exception ignored) {}
                sendMessage(chatId, human);
                return false;
            }
        } catch (Exception e) {
            log.error("sendStarsInvoiceRaw error", e);
            sendMessage(chatId, "Не удалось создать счёт: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String toJson(String s) {
        if (s == null) return "null";
        String escaped = s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
        return "\"" + escaped + "\"";
    }

    private long safeCurrent(TaskLink t) {
        try {
            java.lang.reflect.Method m = TaskLink.class.getMethod("getCurrentSubs");
            Object v = m.invoke(t);
            if (v instanceof Long l) return l;
        } catch (Exception ignored) {}
        return 0L;
    }

    private String generateReferralCode() {
        String chars = "AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz0123456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return code.toString();
    }

    private void processReferralCode(long chatId, String refCode) {
        Optional<User> userOpt = userRepository.findById(chatId);
        if (userOpt.isEmpty()) return;

        User user = userOpt.get();

        if (user.getReferredBy() != null) return;

        if (refCode.equals(user.getRefCode())) return;

        Optional<User> inviterOpt = userRepository.findByRefCode(refCode);
        if (inviterOpt.isEmpty()) return;

        User inviter = inviterOpt.get();

        user.setReferredBy(refCode);
        userRepository.save(user);

        inviter.setBalance(inviter.getBalance() + 2);
        userRepository.save(inviter);

        sendMessage(chatId, "✅ Реферальный код принят! Вы получили +2 ⭐️");
        sendMessage(inviter.getChatId(), "🎉 У вас новый реферал! +2 ⭐️ на баланс");
    }
    private String encForTelegram(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private InlineKeyboardMarkup buildTopupMethodsKb(String target) {
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if ("regular".equals(target)) {
            // 💸 Дёшево купить Звёзды
            InlineKeyboardButton ton = new InlineKeyboardButton();
            ton.setText("TON");
            ton.setCallbackData("TOPUP_TON");

            InlineKeyboardButton sbp = new InlineKeyboardButton();
            sbp.setText("Рубли (СБП)");
            sbp.setCallbackData("TOPUP_SBP");

            InlineKeyboardButton crypto = new InlineKeyboardButton();
            crypto.setText("Криптовалюты");
            crypto.setCallbackData("TOPUP_CRYPTO");

            rows.add(List.of(ton));
            rows.add(List.of(sbp));
            rows.add(List.of(crypto));
        } else {
            // 💳 Пополнение рекламного баланса
            InlineKeyboardButton fromBalance = new InlineKeyboardButton();
            fromBalance.setText("С обычного баланса");
            fromBalance.setCallbackData("TOPUP_FROM_BALANCE");

            InlineKeyboardButton tgStars = new InlineKeyboardButton();
            tgStars.setText("Telegram Stars");
            tgStars.setCallbackData("TOPUP_TGSTARS");

            InlineKeyboardButton sbp = new InlineKeyboardButton();
            sbp.setText("Рубли (СБП)");
            sbp.setCallbackData("TOPUP_SBP");

            InlineKeyboardButton crypto = new InlineKeyboardButton();
            crypto.setText("Криптовалюты");
            crypto.setCallbackData("TOPUP_CRYPTO");

            rows.add(List.of(fromBalance));
            rows.add(List.of(tgStars));
            rows.add(List.of(sbp));
            rows.add(List.of(crypto));
        }

        kb.setKeyboard(rows);
        return kb;
    }

    private void startTopupFlow(long chatId, String username, String target) {
        topupTarget.put(chatId, target);
        userStates.put(chatId, STATE_WAIT_AMOUNT);

        String title = "💳 Пополнение рекламного баланса";
        if ("regular".equals(target)) {
            title = "💸 Дёшево купить Звёзды";
        }

        String text = title + "\n\n" +
                "Введите сумму для пополнения:\n\n" +
                "Минимум: 50⭐️\n\n" +
                "Отменить — /cancel";

        org.telegram.telegrambots.meta.api.methods.send.SendMessage msg = new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(text);

        try {
            execute(msg);
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
            log.error("startTopupFlow error: " + e.getMessage());
        }
    }

    private void resetTopupState(long chatId) {
        userStates.remove(chatId);
        topupTarget.remove(chatId);
        topupAmount.remove(chatId);
    }

    private void registerUser(Message msg) {
        if(userRepository.findById(msg.getChatId()).isEmpty()) {
            var chatId = msg.getChatId();
            var chat = msg.getChat();

            User user = new User();

            user.setChatId(chatId);
            user.setUserName(chat.getUserName());
            user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));
            user.setBalance(1);
            user.setAdsBalance(0);
            user.setWithdrawnBalance(0);
            user.setRefCode(generateReferralCode());

            userRepository.save(user);
            log.info("Зарегистрирован новый пользователь: " + user);
        }
    }

    private void startCommandRecieved(long chatId, String username) {
        SendMessage message = new SendMessage();

        Optional<User> userOpt = userRepository.findById(chatId);

        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Пользователь не найден.");
            return;
        }

        User user = userOpt.get();
        int referralCount = userRepository.countByReferredBy(user.getRefCode());
        message.setChatId(String.valueOf(chatId));
        String botUsername = "tgstarschange_bot";
        String referralLink = "https://t.me/" + botUsername + "?start=" + user.getRefCode();

        message.setChatId(String.valueOf(chatId));
        message.setText("Получай +2 ⭐️ за каждого приглашенного друга!\n\n" +
                "📎 Твоя реферальная ссылка:\n" +
                referralLink + "\n\n" +
                "🎉 Приглашай по этой ссылке своих друзей, отправляй её во все чаты и зарабатывай Звёзды!\n\n" +
                "Приглашено вами: " + referralCount);

        InlineKeyboardMarkup markupInLine = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();

        var show_to_friends_button = new InlineKeyboardButton();
        show_to_friends_button.setText("Отправить ссылку друзьям");

        String refLink = "https://t.me/" + botUsername + "?start=" + user.getRefCode();
        String shareText = "Залетай! Получай ⭐ за подписки. Моя реферальная: " + refLink;

        String url = "https://t.me/share/url?url=" + encForTelegram(refLink) + "&text=" + encForTelegram(shareText);

        show_to_friends_button.setUrl(url);

        rowInLine.add(show_to_friends_button);
        rowsInLine.add(rowInLine);

        markupInLine.setKeyboard(rowsInLine);
        message.setReplyMarkup(markupInLine);

        try {
            execute(message);
            sendMessage(chatId, "\uD83D\uDCAB");
        }
        catch (TelegramApiException e) {
            log.error("START ОШИБКА: " + e.getMessage());
        }
        log.info("Пользователь: " + username + "\n" +
                "TelegramID:" + chatId + "\n" +
                "запустил команду /start или нажал ''⭐\uFE0F Заработать Звёзды''.");
    }

    private void suggestTaskCommandRecieved(long chatId, String username) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("\uD83D\uDCA1Также можно получать Звёзды за простые задания!");

        InlineKeyboardMarkup markupInLine = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();

        var task_button = new InlineKeyboardButton();
        task_button.setText("\uD83D\uDC8E Выполнять Задания");
        task_button.setCallbackData("TASK_BUTTON");

        rowInLine.add(task_button);
        rowsInLine.add(rowInLine);

        markupInLine.setKeyboard(rowsInLine);
        message.setReplyMarkup(markupInLine);
        try {
            execute(message);
        }
        catch (TelegramApiException e) {
            log.error("SUGGEST ОШИБКА: " + e.getMessage());
        }
        log.info("Пользователь: " + username + "\n" +
                "TelegramID:" + chatId + "\n" +
                "предложено начать задания.");
    }

    private void profileCommandRecieved(long chatId, String username) {

        Optional<User> userOpt = userRepository.findById(chatId);

        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Пользователь не найден.");
            return;
        }
        User user = userOpt.get();

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("\uD83D\uDDA5 Мой Кабинет\n" +
                "\n" +
                "    Пользователь: " + username + "\n" +
                "    Баланс:\n" +
                "    Обычный: " + user.getBalance() + " ⭐\uFE0F\n" +
                "    Рекламный: " + user.getAdsBalance() + " ⭐\uFE0F\n" +
                "\n" +
                "    Выведено: " + user.getWithdrawnBalance() + " ⭐\uFE0F\n" +
                "\n" +
                "    ❗\uFE0FЗвёзды выводятся с обычного баланса.\n" +
                "\n" +
                "    \uD83D\uDC65 Хочешь подписчиков в свой Канал? Жми «Создать Задание» и обменивай звёзды на подписчиков!");

        InlineKeyboardMarkup markupInLine = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> rowInLine1 = new ArrayList<>();
        var my_tasks_button = new InlineKeyboardButton();
        my_tasks_button.setText("\uD83D\uDCCB Мои задания");
        my_tasks_button.setCallbackData("MY_TASKS_BUTTON");
        rowInLine1.add(my_tasks_button);
        rowsInLine.add(rowInLine1);

        List<InlineKeyboardButton> rowInLine2 = new ArrayList<>();
        var create_task_button = new InlineKeyboardButton();
        create_task_button.setText("➕ Создать задание");
        create_task_button.setCallbackData("CREATE_TASK_BUTTON");
        rowInLine2.add(create_task_button);
        rowsInLine.add(rowInLine2);

        List<InlineKeyboardButton> rowInLine3 = new ArrayList<>();
        var ads_balance_button = new InlineKeyboardButton();
        ads_balance_button.setText("\uD83D\uDCB3 Пополнить рекламный баланс");
        ads_balance_button.setCallbackData("ADS_BALANCE_BUTTON");
        rowInLine3.add(ads_balance_button);
        rowsInLine.add(rowInLine3);

        markupInLine.setKeyboard(rowsInLine);
        message.setReplyMarkup(markupInLine);
        try {
            execute(message);
        }
        catch (TelegramApiException e) {
            log.error("PROFILE ОШИБКА: " + e.getMessage());
        }
        log.info("Пользователь: " + username + "\n" +
                "TelegramID:" + chatId + "\n" +
                "запустил команду /profile или нажал ''\uD83D\uDC65 Купить Подписчиков''.");
    }

    private void taskCommandRecieved(long chatId, String username) {
        Optional<User> optUser = userRepository.findById(chatId);
        TaskLink chosenTask = null;

        if (optUser.isPresent()) {
            String active = optUser.get().getActiveTask();
            if (active != null && !active.isBlank()) {
                try {
                    long activeId = Long.parseLong(active);
                    Optional<TaskLink> activeTaskOpt = taskRepository.findById(activeId);
                    if (activeTaskOpt.isPresent()) {
                        TaskLink t = activeTaskOpt.get();
                        if (normalizeLink(t.getLink()) != null) {
                            chosenTask = t; // активка валидная
                        } else {
                            // активка битая — очищаем и пойдём ниже подбирать другую
                            optUser.get().setActiveTask(null);
                            userRepository.save(optUser.get());
                        }
                    } else {
                        // активная запись исчезла — очищаем
                        optUser.get().setActiveTask(null);
                        userRepository.save(optUser.get());
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        if (chosenTask == null) {
            java.util.List<TaskLink> all = new java.util.ArrayList<>();
            taskRepository.findAll().forEach(all::add);

            if (all.isEmpty()) {
                sendMessage(chatId, "\uD83D\uDE14 Заданий пока нет.");
                return;
            }

            // каналы, за которые этому юзеру уже платили
            java.util.Set<Long> done = rewardedSet(chatId);

            TaskLink picked = null;
            for (int i = 0; i < all.size(); i++) {
                TaskLink cand = all.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(all.size()));
                String linkOk = normalizeLink(cand.getLink());
                if (linkOk != null && !done.contains(cand.getChatId())) {
                    picked = cand;
                    break;
                }
            }

            if (picked == null) {
                sendMessage(chatId, "\uD83D\uDE14 К сожалению задания закончились, загляните позже!\n");
                return;
            }

            chosenTask = picked;

            // запомним активное задание
            TaskLink finalChosenTask = chosenTask;
            userRepository.findById(chatId).ifPresent(u -> {
                u.setActiveTask(String.valueOf(finalChosenTask.getChatId()));
                userRepository.save(u);
            });
        }

        // 3) ссылка гарантированно валидная (мы фильтровали)
        String openUrl = normalizeLink(chosenTask.getLink());

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(
                "Подпишитесь на канал " + openUrl + " и нажмите «Подтвердить»\n\n" +
                        "Вознаграждение: +1⭐️\n\n" +
                        "У вас есть 5 минут на выполнение этого задания!");

        InlineKeyboardMarkup markupInLine = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> rowInLine1 = new ArrayList<>();
        InlineKeyboardButton go_to_channel_button = new InlineKeyboardButton();
        go_to_channel_button.setText("\uD83D\uDC5E Перейти");
        go_to_channel_button.setUrl(openUrl); // URL-кнопка — БЕЗ callbackData
        rowInLine1.add(go_to_channel_button);

        InlineKeyboardButton done_button = new InlineKeyboardButton();
        done_button.setText("✅ Подтвердить");
        done_button.setCallbackData("DONE_BUTTON");
        rowInLine1.add(done_button);
        rowsInLine.add(rowInLine1);

        List<InlineKeyboardButton> rowInLine2 = new ArrayList<>();
        InlineKeyboardButton skip_task_button = new InlineKeyboardButton();
        skip_task_button.setText("⏩ Пропустить");
        skip_task_button.setCallbackData("SKIP_TASK_BUTTON");
        rowInLine2.add(skip_task_button);
        rowsInLine.add(rowInLine2);

        markupInLine.setKeyboard(rowsInLine);
        message.setReplyMarkup(markupInLine);

        try { execute(message); } catch (TelegramApiException e) {
            log.error("TASK ОШИБКА: " + e.getMessage());
        }
    }


    private String normalizeLink(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.isEmpty()) return null;

        if (u.matches("^@?[A-Za-z0-9_]{5,}$")) {
            u = u.replaceFirst("^@", "");
            return "https://t.me/" + u;
        }

        if (u.startsWith("t.me/")) {
            return "https://" + u;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }

        return u.matches("(?i)^https?://t\\.me/.+") ? u : null;
    }

    private void withdrawCommandRecieved(long chatId, String username) {

        Optional<User> userOpt = userRepository.findById(chatId);

        if (userOpt.isEmpty()) {
            sendMessage(chatId, "Пользователь не найден.");
            return;
        }
        User user = userOpt.get();

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Заработано: " + user.getBalance() + " ⭐\uFE0F\n" +
                "\n" +
                "Выберите сумму для вывода\n" +
                "\n" +
                "Канал с выводами: @StarsovEarnOut");

        InlineKeyboardMarkup markupInLine = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> rowInLine1 = new ArrayList<>();
        var fifty_stars_button = new InlineKeyboardButton();
        fifty_stars_button.setText("25⭐\uFE0F");
        fifty_stars_button.setCallbackData("25_STARS");
        rowInLine1.add(fifty_stars_button);
        var twenty_five_stars_button = new InlineKeyboardButton();
        twenty_five_stars_button.setText("50⭐\uFE0F");
        twenty_five_stars_button.setCallbackData("50_STARS");
        rowInLine1.add(twenty_five_stars_button);
        rowsInLine.add(rowInLine1);

        List<InlineKeyboardButton> rowInLine2 = new ArrayList<>();
        var fifteen_button = new InlineKeyboardButton();
        fifteen_button.setText("100⭐\uFE0F");
        fifteen_button.setCallbackData("100_STARS");
        rowInLine2.add(fifteen_button);
        var hundred_stars_button = new InlineKeyboardButton();
        hundred_stars_button.setText("200⭐\uFE0F");
        hundred_stars_button.setCallbackData("200_STARS");
        rowInLine2.add(hundred_stars_button);
        rowsInLine.add(rowInLine2);

        markupInLine.setKeyboard(rowsInLine);
        message.setReplyMarkup(markupInLine);
        try {
            execute(message);
        }
        catch (TelegramApiException e) {
            log.error("WITHDRAW ОШИБКА: " + e.getMessage());
        }
        log.info("Пользователь: " + username + "\n" +
                "TelegramID:" + chatId + "\n" +
                "нажал на кнопку ''\uD83C\uDF81 Вывести Звёзды''.");
    }

    private void buyStarsCommandRecieved(long chatId, String username) {
        startTopupFlow(chatId, username, "regular");
    }

    private void sendMessage(long chatId, String textToSend) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);

        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboardRows = new ArrayList<>();

        KeyboardRow row = new KeyboardRow();
        row.add("⭐\uFE0F Заработать Звёзды");
        row.add("\uD83D\uDC8E Задания");
        keyboardRows.add(row);

        row = new KeyboardRow();
        row.add("\uD83C\uDF81 Вывести Звёзды");
        keyboardRows.add(row);

        row = new KeyboardRow();
        row.add("\uD83D\uDC65 Купить Подписчиков");
        keyboardRows.add(row);

        row = new KeyboardRow();
        row.add("\uD83D\uDCB8 Дёшево Купить Звёзды");
        keyboardRows.add(row);

        keyboardMarkup.setKeyboard(keyboardRows);
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(false);
        keyboardMarkup.setSelective(false);
        keyboardMarkup.setIsPersistent(true);
        message.setReplyMarkup(keyboardMarkup);

        try {
            execute(message);
        }
        catch (TelegramApiException e) {
            log.error("ОШИБКА: " + e.getMessage());
        }
    }
}

