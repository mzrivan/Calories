package kz.calories.dish;

import java.util.List;
import java.util.Optional;
import kz.calories.config.AppProperties;
import org.springframework.stereotype.Service;

/** Поиск блюда по названию без LLM: точное имя → алиас → pg_trgm. */
@Service
public class DishMatcher {

    public enum Type { EXACT, APPROX, UNKNOWN }

    /** candidates — для APPROX/UNKNOWN: ближайшие варианты (для подсказок агенту). */
    public record Match(Type type, DishVersion version, double score, List<DishRepository.Scored> candidates) {
        static Match exact(DishVersion v) {
            return new Match(Type.EXACT, v, 1.0, List.of());
        }
    }

    private final DishRepository repo;
    private final AppProperties props;

    public DishMatcher(DishRepository repo, AppProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public Match match(String name) {
        String norm = Names.normalize(name);
        if (norm.isEmpty()) {
            return new Match(Type.UNKNOWN, null, 0, List.of());
        }
        Optional<DishVersion> exact = repo.latestActive(norm);
        if (exact.isPresent()) {
            return Match.exact(exact.get());
        }
        Optional<DishVersion> viaAlias = repo.aliasTarget(norm).flatMap(repo::latestActive);
        if (viaAlias.isPresent()) {
            return Match.exact(viaAlias.get());
        }
        AppProperties.Search cfg = props.search();
        List<DishRepository.Scored> found = repo.searchTrgm(norm, cfg.trgmThreshold(), cfg.topK());
        if (found.isEmpty()) {
            return new Match(Type.UNKNOWN, null, 0, List.of());
        }
        DishRepository.Scored best = found.get(0);
        double second = found.size() > 1 ? found.get(1).score() : 0;
        if (best.score() >= cfg.approxThreshold() && best.score() - second >= cfg.approxGap()) {
            return new Match(Type.APPROX, best.version(), best.score(), found);
        }
        return new Match(Type.UNKNOWN, null, best.score(), found);
    }

    /** Кандидаты для инструмента агента: точное/алиас + trgm, лучшие первыми. */
    public List<DishRepository.Scored> candidates(String name) {
        String norm = Names.normalize(name);
        if (norm.isEmpty()) {
            return List.of();
        }
        Match m = match(name);
        if (m.type() == Type.EXACT) {
            return List.of(new DishRepository.Scored(m.version(), 1.0, "exact"));
        }
        return m.candidates();
    }
}
