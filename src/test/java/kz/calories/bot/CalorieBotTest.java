package kz.calories.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.telegram.telegrambots.meta.api.objects.Update;
import kz.calories.agent.AgentContext;
import kz.calories.agent.AgentReply;
import kz.calories.agent.RecordRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginUser;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

class CalorieBotTest {
    private final TelegramClient client = mock(TelegramClient.class);
    private final MessageRouter router = mock(MessageRouter.class);
    private final MessageActions actions = mock(MessageActions.class);
    private final CalorieBot bot = new CalorieBot("123:token", client, router, actions);
    private final List<SendMessage> sent = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setup() throws Exception {
        when(client.execute(any(SendMessage.class))).thenAnswer(call -> {
            sent.add(call.getArgument(0));
            Message result = new Message();
            result.setMessageId(sent.size());
            return result;
        });
    }

    @Test
    void noUndoOrCancelButtonsAndEachMessageRetainsItsRecordReferences() throws Exception {
        var refs = List.of(new RecordRef(RecordRef.Kind.MEAL, 42));
        var reply = new AgentReply("Записано", List.of(AgentContext.DraftCard.meals(7, "Черновик еды"),
                AgentContext.DraftCard.dish(8, "Черновик блюда"))).withUndo("old-batch-id").withRecords(refs);
        when(router.handleText(1001, "User", "Хлеб 30")).thenReturn(Optional.of(reply));
        bot.onMessage(message(1001, "Хлеб 30"));

        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).getReplyMarkup()).isNull();
        for (SendMessage card : sent.subList(1, 3)) {
            InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) card.getReplyMarkup();
            assertThat(keyboard.getKeyboard().getFirst()).hasSize(1);
            assertThat(keyboard.getKeyboard().getFirst().getFirst().getCallbackData()).doesNotContain("cancel", "undo");
        }
        verify(actions).remember(1001, "User", 1001, 1, "Записано", refs);
        verify(actions).remember(1001, "User", 1001, 2, "Черновик еды",
                List.of(new RecordRef(RecordRef.Kind.MEAL_DRAFT, 7)));
        verify(actions).remember(1001, "User", 1001, 3, "Черновик блюда",
                List.of(new RecordRef(RecordRef.Kind.DISH_DRAFT, 8)));
    }

    @Test
    void forwardedBotMessageIsSelectedInsteadOfLoggedAsFood() throws Exception {
        Message forwarded = message(1001, "Записано: хлеб 30 г");
        forwarded.setForwardOrigin(MessageOriginUser.builder().date(1).senderUser(user(123)).build());
        bot.onMessage(forwarded);
        verify(actions).reference(1001, "User", 1001, null, forwarded.getText(), null);
        verifyNoInteractions(router);
    }

    @Test
    void replySuppliesOriginalMessageIdAndInstruction() throws Exception {
        Message request = message(1001, "удали");
        Message original = message(123, "Записано");
        original.setMessageId(88);
        request.setReplyToMessage(original);
        bot.onMessage(request);
        verify(actions).reference(1001, "User", 1001, 88, "Записано", "удали");
        verifyNoInteractions(router);
    }

    @Test
    void commandIsAnsweredWhileLlmMessageIsStillProcessing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(router.handleText(1001, "User", "Съел что-то непонятное")).thenAnswer(call -> {
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(AgentReply.text("медленный ответ"));
        });
        when(router.handleText(1001, "User", "/today")).thenReturn(Optional.of(AgentReply.text("итоги")));

        bot.consume(update(message(1001, "Съел что-то непонятное")));
        bot.consume(update(message(1001, "/today")));

        verify(client, timeout(2000)).execute(any(SendMessage.class));
        assertThat(sent).extracting(SendMessage::getText).containsExactly("итоги");
        release.countDown();
        verify(client, timeout(2000).times(2)).execute(any(SendMessage.class));
        assertThat(sent).extracting(SendMessage::getText).containsExactly("итоги", "медленный ответ");
    }

    @Test
    void llmMessagesOfOnePersonAreProcessedInOrder() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(router.handleText(1001, "User", "Съел что-то непонятное")).thenAnswer(call -> {
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(AgentReply.text("первый"));
        });
        when(router.handleText(1001, "User", "Нет, это было другое")).thenReturn(Optional.of(AgentReply.text("второй")));

        bot.consume(update(message(1001, "Съел что-то непонятное")));
        bot.consume(update(message(1001, "Нет, это было другое")));

        verify(router, after(300).never()).handleText(1001, "User", "Нет, это было другое");
        release.countDown();
        verify(client, timeout(2000).times(2)).execute(any(SendMessage.class));
        assertThat(sent).extracting(SendMessage::getText).containsExactly("первый", "второй");
    }

    @Test
    void selectionIsTakenOnReceiptAndSimpleDeleteSkipsLlmQueue() throws Exception {
        var selection = new MessageActions.Selection(null, new BotMessageRepository.Stored(5, "Записано", List.of()));
        when(actions.takeSelection(1001, "User", 1001)).thenReturn(Optional.of(selection));
        when(actions.apply(selection, "удали")).thenReturn(AgentReply.text("Запись деактивирована"));

        bot.consume(update(message(1001, "удали")));

        verify(client, timeout(2000)).execute(any(SendMessage.class));
        assertThat(sent).extracting(SendMessage::getText).containsExactly("Запись деактивирована");
        verifyNoInteractions(router);
        verify(actions, never()).pending(anyLong(), any(), anyLong(), any());
    }

    private static Update update(Message message) {
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private static User user(long id) {
        return User.builder().id(id).firstName("User").isBot(id == 123).build();
    }

    private static Message message(long senderId, String text) {
        Message message = new Message();
        message.setMessageId(10);
        message.setFrom(user(senderId));
        message.setChat(Chat.builder().id(1001L).type("private").build());
        message.setText(text);
        return message;
    }
}
