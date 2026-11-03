package kz.calories.dish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kz.calories.config.AppProperties;
import kz.calories.llm.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DishService {

    private static final Logger log = LoggerFactory.getLogger(DishService.class);

    public record Draft(long id, long userId, DraftPayload payload, String status, OffsetDateTime expiresAt) {}

    public record Candidate(DishVersion version, double score, String matchType) {}

    private final DishRepository repo;
    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public DishService(DishRepository repo, JdbcClient jdbc, EmbeddingClient embeddings, ObjectMapper mapper,
                       AppProperties props) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.mapper = mapper;
        this.props = props;
    }

    // ---------- черновики ----------

    /** Создаёт черновик; предыдущие ожидающие черновики пользователя заменяются (правка текстом = новый черновик). */
    @Transactional
    public long createDraft(long userId, DraftPayload payload) {
        jdbc.sql("update dish_draft set status = 'SUPERSEDED' where user_id = :u and status = 'PENDING'")
                .param("u", userId).update();
        return jdbc.sql("""
                insert into dish_draft (user_id, payload, expires_at)
                values (:u, cast(:p as jsonb), now() + make_interval(hours => :h)) returning id""")
                .param("u", userId).param("p", toJson(payload)).param("h", props.drafts().ttlHours())
                .query(Long.class).single();
    }

    public Optional<Draft> findDraft(long id) {
        return jdbc.sql("select id, user_id, payload::text as payload, status, expires_at from dish_draft where id = :id")
                .param("id", id)
                .query((rs, i) -> new Draft(rs.getLong("id"), rs.getLong("user_id"),
                        fromJson(rs.getString("payload")), rs.getString("status"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
    }

    /** Подтверждение кнопкой: создаёт новую иммутабельную версию с эмбеддингом. */
    @Transactional
    public DishVersion confirmDraft(long draftId, long userId) {
        Draft d = findDraft(draftId).orElseThrow(() -> new IllegalArgumentException("Черновик не найден"));
        if (d.userId() != userId) {
            throw new IllegalArgumentException("Это чужой черновик");
        }
        if (!"PENDING".equals(d.status())) {
            throw new IllegalStateException("Черновик уже обработан или заменён новым (статус " + d.status() + ")");
        }
        if (d.expiresAt().isBefore(OffsetDateTime.now())) {
            setDraftStatus(draftId, "EXPIRED");
            throw new IllegalStateException("Черновик устарел, отправьте описание заново");
        }
        String literal = null;
        try {
            literal = toLiteral(embeddings.embed(Names.normalize(d.payload().name())));
        } catch (RuntimeException e) {
            log.warn("Не удалось получить эмбеддинг для '{}', версия сохранится без него: {}", d.payload().name(), e.toString());
        }
        long id = repo.insert(d.payload(), userId, literal);
        setDraftStatus(draftId, "CONFIRMED");
        return repo.findActiveById(id).orElseThrow();
    }

    public boolean cancelDraft(long draftId, long userId) {
        return jdbc.sql("update dish_draft set status = 'CANCELLED' where id = :id and user_id = :u and status = 'PENDING'")
                .param("id", draftId).param("u", userId).update() > 0;
    }

    private void setDraftStatus(long id, String status) {
        jdbc.sql("update dish_draft set status = :s where id = :id").param("s", status).param("id", id).update();
    }

    // ---------- поиск ----------

    /** exact + pg_trgm + pgvector, дедупликация по блюду, самая свежая активная версия каждого. */
    public List<Candidate> search(String query) {
        String norm = Names.normalize(query);
        if (norm.isEmpty()) {
            return List.of();
        }
        AppProperties.Search cfg = props.search();
        Map<String, Candidate> byDish = new LinkedHashMap<>();
        for (DishRepository.Scored s : repo.searchExact(norm)) {
            merge(byDish, s);
        }
        for (DishRepository.Scored s : repo.searchTrgm(norm, cfg.trgmThreshold(), cfg.topK())) {
            merge(byDish, s);
        }
        try {
            String lit = toLiteral(embeddings.embed(norm));
            for (DishRepository.Scored s : repo.searchVector(lit, cfg.vectorThreshold(), cfg.topK())) {
                merge(byDish, s);
            }
        } catch (RuntimeException e) {
            log.warn("Векторный поиск пропущен: {}", e.toString());
        }
        List<Candidate> result = new ArrayList<>(byDish.values());
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result.size() > cfg.topK() ? result.subList(0, cfg.topK()) : result;
    }

    private void merge(Map<String, Candidate> map, DishRepository.Scored s) {
        String key = s.version().normalizedName();
        Candidate old = map.get(key);
        if (old == null || s.score() > old.score()) {
            String type = old == null ? s.matchType() : old.matchType() + "+" + s.matchType();
            map.put(key, new Candidate(s.version(), s.score(), type));
        }
    }

    public Optional<DishVersion> findActiveVersion(long id) {
        return repo.findActiveById(id);
    }

    /** Версии блюда по id любой его версии или по названию. */
    public List<DishVersion> versions(Long versionId, String name) {
        String norm = null;
        if (versionId != null) {
            norm = jdbc.sql("select normalized_name from dish_version where id = :id").param("id", versionId)
                    .query(String.class).optional().orElse(null);
        }
        if (norm == null && name != null) {
            norm = Names.normalize(name);
        }
        return norm == null ? List.of() : repo.versionsOf(norm);
    }

    public List<DishVersion> listLatest(String filter, int limit) {
        return repo.listLatest(Names.normalize(filter), limit);
    }

    /** Мягкая деактивация версии; актуальной становится предыдущая активная. */
    public Optional<DishVersion> deactivate(long versionId) {
        Optional<DishVersion> v = repo.findActiveById(versionId);
        if (v.isEmpty() || !repo.deactivate(versionId)) {
            return Optional.empty();
        }
        return v;
    }

    public Optional<DishVersion> latestActive(String name) {
        return repo.latestActive(Names.normalize(name));
    }

    // ---------- util ----------

    public static String toLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private String toJson(DraftPayload p) {
        try {
            return mapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private DraftPayload fromJson(String json) {
        try {
            return mapper.readValue(json, DraftPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
