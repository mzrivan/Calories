package kz.calories.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import kz.calories.agent.AgentReply;
import kz.calories.agent.AgentService;
import kz.calories.agent.ChatRepository;
import kz.calories.agent.RecordRef;
import kz.calories.dish.DishService;
import kz.calories.energy.ActivityService;
import kz.calories.meal.MealBatchService;
import kz.calories.meal.MealEntry;
import kz.calories.meal.MealService;
import kz.calories.meal.MealType;
import kz.calories.user.AppUser;
import kz.calories.user.UserService;
import kz.calories.validation.Nutrition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class MessageActionsTest {
    private final UserService users = mock(UserService.class);
    private final BotMessageRepository messages = mock(BotMessageRepository.class);
    private final MealService meals = mock(MealService.class);
    private final MealBatchService batches = mock(MealBatchService.class);
    private final AgentService agent = mock(AgentService.class);
    private final ChatRepository chat = mock(ChatRepository.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final AppUser user = new AppUser(1, 1001, "User", ZoneId.of("UTC"),
            new AppUser.Goals(null, null, null, null, null), AppUser.Profile.EMPTY);
    private final RecordRef ref = new RecordRef(RecordRef.Kind.MEAL, 42);
    private final BotMessageRepository.Stored stored = new BotMessageRepository.Stored(7, "Хлеб, 30 г", List.of(ref));
    private final MessageActions actions = new MessageActions(users, messages, meals, mock(ActivityService.class),
            mock(DishService.class), batches, agent, chat, tx);

    @BeforeEach
    void setup() {
        when(users.resolve(1001, "User")).thenReturn(Optional.of(user));
        when(tx.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"удали", "отмени", "Отмена!", "Пожалуйста, удали запись!"})
    void forwardSelectsRecordAndNextMessageDeactivatesItWithoutLlm(String command) {
        when(messages.findForward(1, stored.text())).thenReturn(Optional.of(stored));
        AgentReply prompt = actions.reference(1001, "User", 1001, null, stored.text(), null).orElseThrow();
        assertThat(prompt.text()).contains("Напишите");
        assertThat(prompt.records()).containsExactly(ref);
        verify(messages).select(1, 1001, 7);
        verifyNoInteractions(meals, agent);

        when(messages.selected(1, 1001)).thenReturn(Optional.of(stored));
        when(meals.deactivate(user, 42)).thenReturn(Optional.of(new MealEntry(42, 1, OffsetDateTime.now(),
                "Хлеб", null, 30, Nutrition.ZERO, "HIGH", null, MealType.OTHER)));
        when(meals.todayBlock(user)).thenReturn("Итого за сегодня");
        assertThat(actions.pending(1001, "User", 1001, command).orElseThrow().text()).contains("деактивирована");
        verify(meals).deactivate(user, 42);
        verifyNoInteractions(agent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"измени", "не 30, а 50 г", "что-то непонятное", "не удаляй", "удали только хлеб"})
    void editsAndAmbiguousInstructionsGoToLlmWithExactRecordContext(String instruction) {
        when(messages.find(1, 1001, 99)).thenReturn(Optional.of(stored));
        when(agent.handle(eq(user), any())).thenReturn(AgentReply.text("Ответ LLM"));
        assertThat(actions.reference(1001, "User", 1001, 99, stored.text(), instruction).orElseThrow().text())
                .isEqualTo("Ответ LLM");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agent).handle(eq(user), prompt.capture());
        assertThat(prompt.getValue()).contains("kind=MEAL, id=42", stored.text(), instruction);
        verifyNoInteractions(meals);
    }

    @Test
    void replyToSelectionPromptKeepsOriginalMessageContext() {
        var prompt = new BotMessageRepository.Stored(8, "Напишите, что сделать", stored.records());
        when(messages.selected(1, 1001)).thenReturn(Optional.of(stored));
        when(messages.find(1, 1001, 99)).thenReturn(Optional.of(prompt));
        when(agent.handle(eq(user), any())).thenReturn(AgentReply.text("Уточните вес"));
        actions.reference(1001, "User", 1001, 99, prompt.text(), "измени");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(agent).handle(eq(user), text.capture());
        assertThat(text.getValue()).contains(stored.text(), "id=42");
    }

    @Test
    void missingOrAmbiguousForwardDoesNotChangeAnything() {
        when(messages.findForward(1, stored.text())).thenReturn(Optional.empty());
        assertThat(actions.reference(1001, "User", 1001, null, stored.text(), "удали").orElseThrow().text())
                .contains("Не удалось однозначно");
        verifyNoInteractions(meals, agent);
    }

    @Test
    void draftCancellationUsesDraftServiceRatherThanMealId() {
        var draft = new BotMessageRepository.Stored(8, "Черновик", List.of(new RecordRef(RecordRef.Kind.MEAL_DRAFT, 42)));
        when(messages.find(1, 1001, 99)).thenReturn(Optional.of(draft));
        when(batches.cancel(user, 42)).thenReturn(true);
        actions.reference(1001, "User", 1001, 99, draft.text(), "отмени");
        verify(batches).cancel(user, 42);
        verifyNoInteractions(agent);
        verify(meals, never()).deactivate(any(), anyLong());
    }

    @Test
    void unauthorizedUserCannotSelectOrModifyRecords() {
        assertThat(actions.reference(999, "Other", 1001, 99, stored.text(), "удали")).isEmpty();
        assertThat(actions.pending(999, "Other", 1001, "удали")).isEmpty();
        verifyNoInteractions(messages, meals, agent);
    }
}
