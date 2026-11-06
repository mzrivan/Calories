package kz.calories.bot;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import kz.calories.agent.AgentContext;
import kz.calories.agent.AgentReply;
import kz.calories.agent.RecordRef;
import kz.calories.config.AppProperties;
import kz.calories.parse.IntentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginUser;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/** Тонкий слой над Telegram API (long polling): вся логика в MessageRouter. */
@Component
@ConditionalOnProperty(name = "app.telegram.enabled", havingValue = "true", matchIfMissing = true)
public class CalorieBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(CalorieBot.class);
    private static final int TG_LIMIT = 4000;
    private static final int SEND_ATTEMPTS = 5;
    private static final long SEND_BASE_DELAY_MS = 1000; // 1, 2, 4, 8 с

    private final String token;
    private final TelegramClient client;
    private final MessageRouter router;
    private final MessageActions actions;
    private final long botId;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * Две очереди на человека. Быстрая (команды, кнопки, простое удаление, пересылка выбора) работает только с БД
     * и не ждёт LLM. Медленная (текст, который может уйти в LLM) выполняется строго по порядку, чтобы правка
     * «нет, 50 г» шла после исходной записи. Разные люди обрабатываются параллельно.
     */
    private final Map<Long, CompletableFuture<Void>> fastChains = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Void>> slowChains = new ConcurrentHashMap<>();

    private static final Set<String> FAST_COMMANDS = Set.of(
            "/start", "/help", "/today", "/del", "/delmeal", "/delact", "/deldish", "/balance", "/dishes");

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    @Autowired
    public CalorieBot(AppProperties props, MessageRouter router, MessageActions actions) {
        this(requireToken(props.telegram().token()), new OkHttpTelegramClient(requireToken(props.telegram().token())), router, actions);
        executor.submit(this::registerCommands);
    }

    CalorieBot(String token, TelegramClient client, MessageRouter router, MessageActions actions) {
        this.token = requireToken(token);
        this.client = client;
        this.router = router;
        this.actions = actions;
        this.botId = parseBotId(token);
    }

    /** Id бота — часть токена до двоеточия; для нестандартного токена -1, и пересылки от бота не распознаются. */
    private static long parseBotId(String token) {
        int colon = token.indexOf(':');
        try {
            return colon > 0 ? Long.parseLong(token.substring(0, colon)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Не задан TELEGRAM_BOT_TOKEN");
        }
        return token;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (update.hasCallbackQuery()) {
            CallbackQuery cb = update.getCallbackQuery();
            enqueue(fastChains, cb.getFrom().getId(), () -> onCallback(cb));
            return;
        }
        if (!(update.hasMessage() && update.getMessage().hasText() && update.getMessage().getFrom() != null)) {
            return; // не текст (фото, голос и т.д.) — не обрабатываем
        }
        Message m = update.getMessage();
        long uid = m.getFrom().getId();
        String text = m.getText().trim();
        Message referenced = m.getReplyToMessage();
        boolean fast;
        Optional<MessageActions.Selection> selection = null; // null — выбор не читали (пересылка, ответ, команда)
        if (m.getForwardOrigin() != null) {
            fast = true; // только запоминаем выбор
        } else if (referenced != null && referencesBot(referenced)) {
            fast = MessageActions.simpleDelete(text);
        } else if (text.startsWith("/")) {
            fast = FAST_COMMANDS.contains(text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT).replaceAll("@.*$", ""));
        } else {
            selection = takeSelection(m);
            fast = selection.isPresent() ? MessageActions.simpleDelete(text) : isFastText(text);
        }
        Optional<MessageActions.Selection> taken = selection;
        if (fast) {
            enqueue(fastChains, uid, () -> handle(m, taken));
        } else {
            enqueue(slowChains, uid, () -> {
                typing(m.getChatId());
                handle(m, taken);
            });
        }
    }

    /** Ставит задачу в очередь человека: задачи одной очереди идут строго по порядку, ошибка не рвёт цепочку. */
    private void enqueue(Map<Long, CompletableFuture<Void>> chains, long uid, Task task) {
        chains.compute(uid, (k, prev) -> (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                .thenRunAsync(() -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        log.error("Ошибка обработки обновления", e);
                    }
                }, executor));
    }

    private Optional<MessageActions.Selection> takeSelection(Message m) {
        try {
            return actions.takeSelection(m.getFrom().getId(), m.getFrom().getFirstName(), m.getChatId());
        } catch (RuntimeException e) {
            log.error("Не удалось прочитать выбранное сообщение", e);
            return Optional.empty();
        }
    }

    /** Текст, который разбирается без LLM: справка или только намерения (вес, шаги, итог дня и т.п.) без остатка. */
    private static boolean isFastText(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        if (low.equals("справка") || low.equals("помощь")) {
            return true;
        }
        IntentParser.Intent intent = IntentParser.parse(text);
        return intent.any() && intent.remainder().isBlank();
    }

    private boolean referencesBot(Message referenced) {
        return forwardedFromBot(referenced) || (referenced.getFrom() != null && referenced.getFrom().getId() == botId);
    }

    private void typing(long chatId) {
        try {
            client.execute(SendChatAction.builder().chatId(chatId).action("typing").build());
        } catch (Exception e) {
            log.debug("Не удалось отправить typing: {}", e.toString());
        }
    }

    /**
     * Отправка в Telegram с повтором при сетевых сбоях (нет сети, таймаут). Ответы API с ошибкой
     * (TelegramApiRequestException: 400/403 и т.п.) не повторяются — результат был бы тот же.
     */
    private <T extends java.io.Serializable, M extends BotApiMethod<T>> T send(M method) throws TelegramApiException {
        TelegramApiException last = null;
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                return client.execute(method);
            } catch (TelegramApiRequestException e) {
                throw e;
            } catch (TelegramApiException e) {
                last = e;
                if (attempt == SEND_ATTEMPTS) {
                    break;
                }
                long delayMs = SEND_BASE_DELAY_MS << (attempt - 1);
                log.warn("Telegram недоступен ({}), повтор {}/{} через {} мс", rootMessage(e), attempt,
                        SEND_ATTEMPTS - 1, delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.toString();
    }

    void onMessage(Message m) throws Exception {
        handle(m, null);
    }

    /** selection: null — выбор не снимали заранее (тогда читаем сейчас), empty — выбора не было. */
    private void handle(Message m, Optional<MessageActions.Selection> selection) throws Exception {
        var from = m.getFrom();
        Optional<AgentReply> reply;
        Message referenced = m.getReplyToMessage();
        if (m.getForwardOrigin() != null) {
            if (!forwardedFromBot(m)) {
                reply = actions.unsupportedForward(from.getId(), from.getFirstName(), m.getChatId());
            } else {
                reply = actions.reference(from.getId(), from.getFirstName(), m.getChatId(), null, m.getText(), null);
            }
        } else if (referenced != null && forwardedFromBot(referenced)) {
            reply = actions.reference(from.getId(), from.getFirstName(), m.getChatId(), null, referenced.getText(), m.getText());
        } else if (referenced != null && referenced.getFrom() != null && referenced.getFrom().getId() == botId) {
            reply = actions.reference(from.getId(), from.getFirstName(), m.getChatId(), referenced.getMessageId(),
                    referenced.getText(), m.getText());
        } else {
            // команды не считаются правкой выбранного сообщения и не сбрасывают выбор
            if (m.getText().startsWith("/")) {
                reply = Optional.empty();
            } else if (selection != null) {
                reply = selection.map(s -> actions.apply(s, m.getText()));
            } else {
                reply = actions.pending(from.getId(), from.getFirstName(), m.getChatId(), m.getText());
            }
            if (reply.isEmpty()) {
                reply = router.handleText(from.getId(), from.getFirstName(), m.getText());
            }
        }
        if (reply.isEmpty()) {
            return; // чужой или неактивный — игнорируем молча
        }
        AgentReply r = reply.get();
        String lower = m.getText().trim().toLowerCase();
        boolean showMenu = lower.startsWith("/start") || lower.startsWith("/help");
        List<String> chunks = split(r.text());
        for (int i = 0; i < chunks.size(); i++) {
            SendMessage.SendMessageBuilder<?, ?> msg = SendMessage.builder().chatId(m.getChatId()).text(chunks.get(i));
            boolean last = i == chunks.size() - 1;
            if (last && showMenu) {
                msg.replyMarkup(menu());
            }
            Message sent = send(msg.build());
            actions.remember(from.getId(), from.getFirstName(), m.getChatId(), sent.getMessageId(), chunks.get(i), r.records());
        }
        for (AgentContext.DraftCard card : r.cards()) {
            InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder().text(card.confirmPrefix().equals("save") ? "✅ Сохранить" : "✅ Записать")
                                    .callbackData(card.confirmPrefix() + ":" + card.draftId()).build()))
                    .build();
            Message sent = send(SendMessage.builder().chatId(m.getChatId()).text(card.text()).replyMarkup(kb).build());
            RecordRef.Kind kind = card.confirmPrefix().equals("save") ? RecordRef.Kind.DISH_DRAFT : RecordRef.Kind.MEAL_DRAFT;
            actions.remember(from.getId(), from.getFirstName(), m.getChatId(), sent.getMessageId(), card.text(),
                    List.of(new RecordRef(kind, card.draftId())));
        }
    }

    private boolean forwardedFromBot(Message message) {
        return message.getForwardOrigin() instanceof MessageOriginUser origin && origin.getSenderUser().getId() == botId;
    }

    /** Постоянная клавиатура: тексты кнопок разбираются теми же ключевыми словами, что и обычный текст. */
    private static ReplyKeyboardMarkup menu() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(
                        KeyboardButton.builder().text("Итог дня").build(),
                        KeyboardButton.builder().text("Баланс").build(),
                        KeyboardButton.builder().text("Справка").build()))
                .resizeKeyboard(true).build();
    }

    private void registerCommands() {
        try {
            client.execute(SetMyCommands.builder()
                    .command(new BotCommand("today", "Итоги дня"))
                    .command(new BotCommand("balance", "Расход и баланс"))
                    .command(new BotCommand("weight", "Записать вес"))
                    .command(new BotCommand("steps", "Записать шаги"))
                    .command(new BotCommand("profile", "Рост, возраст, пол"))
                    .command(new BotCommand("dishes", "Сохранённые блюда"))
                    .command(new BotCommand("help", "Справка"))
                    .build());
        } catch (Exception e) {
            log.warn("Не удалось зарегистрировать меню команд: {}", e.toString());
        }
    }

    private void onCallback(CallbackQuery cb) throws Exception {
        var result = router.handleCallback(cb.getFrom().getId(), cb.getFrom().getFirstName(), cb.getData());
        if (result.isEmpty()) {
            return;
        }
        send(AnswerCallbackQuery.builder().callbackQueryId(cb.getId())
                .text(result.get().toast() == null ? "" : result.get().toast()).build());
        if (result.get().newText() != null && cb.getMessage() != null) {
            send(EditMessageText.builder().chatId(cb.getMessage().getChatId())
                    .messageId(cb.getMessage().getMessageId()).text(result.get().newText()).build());
            actions.remember(cb.getFrom().getId(), cb.getFrom().getFirstName(), cb.getMessage().getChatId(),
                    cb.getMessage().getMessageId(), result.get().newText(), result.get().records());
        }
    }

    static List<String> split(String text) {
        if (text.length() <= TG_LIMIT) {
            return List.of(text);
        }
        List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += TG_LIMIT) {
            parts.add(text.substring(i, Math.min(text.length(), i + TG_LIMIT)));
        }
        return parts;
    }
}
