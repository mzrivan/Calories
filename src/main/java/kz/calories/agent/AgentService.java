package kz.calories.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import kz.calories.config.AppProperties;
import kz.calories.dish.DishMatcher;
import kz.calories.dish.DishService;
import kz.calories.energy.ActivityService;
import kz.calories.energy.BalanceFormatter;
import kz.calories.llm.AgentLlm;
import kz.calories.meal.MealService;
import kz.calories.user.AppUser;
import kz.calories.user.UserService;
import kz.calories.validation.NutritionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Любое сообщение пользователя → LLM-агент с инструментами; итоги дня дописывает код. */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentLlm llm;
    private final ChatRepository chat;
    private final DishService dishes;
    private final DishMatcher matcher;
    private final ActivityService activity;
    private final MealService meals;
    private final UserService users;
    private final NutritionValidator validator;
    private final AppProperties props;
    private final Map<Long, Deque<Long>> recentRequests = new ConcurrentHashMap<>();

    public AgentService(AgentLlm llm, ChatRepository chat, DishService dishes, DishMatcher matcher, ActivityService activity,
                        MealService meals, UserService users, NutritionValidator validator, AppProperties props) {
        this.matcher = matcher;
        this.activity = activity;
        this.llm = llm;
        this.chat = chat;
        this.dishes = dishes;
        this.meals = meals;
        this.users = users;
        this.validator = validator;
        this.props = props;
    }

    public AgentReply handle(AppUser user, String text) {
        if (!allow(user.id())) {
            return AgentReply.text("Слишком много сообщений подряд, подождите минуту и повторите.");
        }
        List<AgentLlm.ChatTurn> history = chat.recent(user.id(), props.agent().historyMessages(), props.agent().historyMinutes());
        chat.save(user.id(), true, text); // сообщение не теряется, даже если LLM недоступна

        AgentContext ctx = new AgentContext();
        AgentTools tools = new AgentTools(user, ctx, dishes, matcher, activity, meals, users, validator, props);
        String answer = null;
        boolean failed = false;
        int attempts = Math.max(1, props.llm().retries());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                answer = llm.chat(SystemPrompt.build(user, promptParams(user)), history, text, tools, props.llm().maxToolCalls()).text();
                failed = false;
                break;
            } catch (RuntimeException e) {
                failed = true;
                log.warn("Ошибка LLM (попытка {}/{}): {}", attempt, attempts, e.toString());
                if (ctx.wrote()) {
                    break; // уже были записи: повтор мог бы продублировать их
                }
                if (attempt < attempts) {
                    sleepBackoff(attempt);
                }
            }
        }

        StringBuilder reply = new StringBuilder();
        if (failed) {
            reply.append(ctx.wrote()
                    ? "Часть действий выполнена, но ответ сформировать не удалось."
                    : "Сейчас не получается обработать сообщение (сервис ИИ перегружен или недоступен). Ваше сообщение сохранено, повторите чуть позже.");
        } else {
            reply.append(answer == null || answer.isBlank() ? "Готово." : answer.trim());
        }
        if (ctx.dayChanged()) {
            AppUser fresh = users.findById(user.id()).orElse(user); // цели могли измениться в этом ходе
            reply.append("\n\n").append(meals.todayBlock(fresh));
        }
        if (ctx.balanceDay() != null) {
            AppUser fresh2 = users.findById(user.id()).orElse(user); // профиль мог измениться в этом ходе
            reply.append("\n\n").append(BalanceFormatter.block(activity.balance(fresh2, ctx.balanceDay())));
        }
        String out = reply.toString();
        chat.save(user.id(), false, out);
        return new AgentReply(out, ctx.cards());
    }

    private SystemPrompt.Params promptParams(AppUser user) {
        try {
            var day = meals.today(user);
            var b = activity.balance(user, day);
            return new SystemPrompt.Params(b.weightKg(), b.steps());
        } catch (RuntimeException e) {
            log.warn("Не удалось собрать параметры пользователя для промпта: {}", e.toString());
            return SystemPrompt.Params.NONE;
        }
    }

    private boolean allow(long userId) {
        long now = System.currentTimeMillis();
        Deque<Long> q = recentRequests.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) {
                q.pollFirst();
            }
            if (q.size() >= props.agent().rateLimitPerMinute()) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private void sleepBackoff(int attempt) {
        long base = props.llm().retryInitialDelayMs() * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
