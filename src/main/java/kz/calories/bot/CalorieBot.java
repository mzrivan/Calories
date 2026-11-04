package kz.calories.bot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import kz.calories.agent.AgentContext;
import kz.calories.agent.AgentReply;
import kz.calories.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
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
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** Сообщения одного человека обрабатываются по порядку, разные люди — параллельно. */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public CalorieBot(AppProperties props, MessageRouter router) {
        this.token = props.telegram().token();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Не задан TELEGRAM_BOT_TOKEN");
        }
        this.client = new OkHttpTelegramClient(token);
        this.router = router;
        executor.submit(this::registerCommands);
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
        Long userId = null;
        if (update.hasMessage() && update.getMessage().hasText() && update.getMessage().getFrom() != null) {
            userId = update.getMessage().getFrom().getId();
        } else if (update.hasCallbackQuery()) {
            userId = update.getCallbackQuery().getFrom().getId();
        }
        if (userId == null) {
            return; // не текст (фото, голос и т.д.) — не обрабатываем
        }
        long uid = userId;
        executor.submit(() -> {
            ReentrantLock lock = locks.computeIfAbsent(uid, k -> new ReentrantLock());
            lock.lock();
            try {
                if (update.hasCallbackQuery()) {
                    onCallback(update.getCallbackQuery());
                } else {
                    onMessage(update.getMessage());
                }
            } catch (Exception e) {
                log.error("Ошибка обработки обновления", e);
            } finally {
                lock.unlock();
            }
        });
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

    private void onMessage(Message m) throws Exception {
        var from = m.getFrom();
        var reply = router.handleText(from.getId(), from.getFirstName(), m.getText());
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
            if (last && r.undoBatchId() != null) {
                msg.replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("↩ Отменить").callbackData("undo:" + r.undoBatchId()).build())).build());
            } else if (last && showMenu) {
                msg.replyMarkup(menu());
            }
            send(msg.build());
        }
        for (AgentContext.DraftCard card : r.cards()) {
            InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder().text(card.confirmPrefix().equals("save") ? "✅ Сохранить" : "✅ Записать")
                                    .callbackData(card.confirmPrefix() + ":" + card.draftId()).build(),
                            InlineKeyboardButton.builder().text("❌ Отмена").callbackData(card.cancelPrefix() + ":" + card.draftId()).build()))
                    .build();
            send(SendMessage.builder().chatId(m.getChatId()).text(card.text()).replyMarkup(kb).build());
        }
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
