package kr.co.page1.knowledge.repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.co.page1.knowledge.domain.KnowledgePattern;
import kr.co.page1.knowledge.domain.PatternCategory;
import kr.co.page1.knowledge.domain.PatternEvidence;
import kr.co.page1.knowledge.domain.PatternScope;
import kr.co.page1.knowledge.domain.PatternStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL over H2. Small enough that hand-written statements are clearer than a
 * mapping layer, and it keeps cold start fast - which matters because stdio mode
 * boots once per MCP client session.
 */
@Repository
public class PatternRepository {

    private static final String COLUMNS = """
            id, fingerprint, category, scope, project_key, statement, anti_pattern, rationale,
            tags, status, hit_count, confidence, first_seen_at, last_seen_at, author, source_ref,
            superseded_by, archived_reason
            """;

    private static final RowMapper<KnowledgePattern> PATTERN_MAPPER = (rs, rowNum) -> {
        KnowledgePattern p = new KnowledgePattern();
        p.setId(rs.getLong("id"));
        p.setFingerprint(rs.getString("fingerprint"));
        p.setCategory(PatternCategory.valueOf(rs.getString("category")));
        p.setScope(PatternScope.valueOf(rs.getString("scope")));
        p.setProjectKey(rs.getString("project_key"));
        p.setStatement(rs.getString("statement"));
        p.setAntiPattern(rs.getString("anti_pattern"));
        p.setRationale(rs.getString("rationale"));
        p.setTags(rs.getString("tags"));
        p.setStatus(PatternStatus.valueOf(rs.getString("status")));
        p.setHitCount(rs.getInt("hit_count"));
        p.setConfidence(rs.getDouble("confidence"));
        p.setFirstSeenAt(rs.getTimestamp("first_seen_at").toInstant());
        p.setLastSeenAt(rs.getTimestamp("last_seen_at").toInstant());
        p.setAuthor(rs.getString("author"));
        p.setSourceRef(rs.getString("source_ref"));
        long superseded = rs.getLong("superseded_by");
        p.setSupersededBy(rs.wasNull() ? null : superseded);
        p.setArchivedReason(rs.getString("archived_reason"));
        return p;
    };

    private static final RowMapper<PatternEvidence> EVIDENCE_MAPPER = (rs, rowNum) -> new PatternEvidence(
            rs.getLong("id"),
            rs.getLong("pattern_id"),
            rs.getString("note"),
            rs.getString("author"),
            rs.getString("source_ref"),
            rs.getDouble("similarity"),
            rs.getTimestamp("recorded_at").toInstant());

    private final JdbcClient jdbc;

    public PatternRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<KnowledgePattern> findByFingerprint(String fingerprint) {
        return jdbc.sql("select " + COLUMNS + " from knowledge_pattern where fingerprint = :fp")
                .param("fp", fingerprint)
                .query(PATTERN_MAPPER)
                .optional();
    }

    public Optional<KnowledgePattern> findById(long id) {
        return jdbc.sql("select " + COLUMNS + " from knowledge_pattern where id = :id")
                .param("id", id)
                .query(PATTERN_MAPPER)
                .optional();
    }

    /**
     * Candidates a new observation could be folded into: same category and same
     * scope key, still alive. Near-duplicate scoring happens in the service so the
     * similarity function stays in one place.
     */
    public List<KnowledgePattern> findMergeCandidates(PatternCategory category, PatternScope scope, String projectKey) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS
                + " from knowledge_pattern where category = :category and scope = :scope and status <> 'ARCHIVED'");
        Map<String, Object> params = new HashMap<>();
        params.put("category", category.name());
        params.put("scope", scope.name());
        if (scope == PatternScope.PROJECT) {
            sql.append(" and project_key = :projectKey");
            params.put("projectKey", projectKey);
        }
        return jdbc.sql(sql.toString()).params(params).query(PATTERN_MAPPER).list();
    }

    /**
     * Rules eligible for injection: every GLOBAL one, plus the PROJECT ones that
     * belong to {@code projectKey}. Ordering is done by the bundle service, which
     * scores recency and frequency together.
     */
    public List<KnowledgePattern> findInjectable(Collection<PatternStatus> statuses, String projectKey) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        List<String> names = statuses.stream().map(PatternStatus::name).toList();
        StringBuilder sql = new StringBuilder("select " + COLUMNS
                + " from knowledge_pattern where status in (:statuses) and (scope = 'GLOBAL'");
        Map<String, Object> params = new HashMap<>();
        params.put("statuses", names);
        if (projectKey != null && !projectKey.isBlank()) {
            sql.append(" or (scope = 'PROJECT' and project_key = :projectKey)");
            params.put("projectKey", projectKey);
        }
        sql.append(") order by last_seen_at desc");
        return jdbc.sql(sql.toString()).params(params).query(PATTERN_MAPPER).list();
    }

    /** Free-form listing used by search, stats and the REST API. */
    public List<KnowledgePattern> search(PatternCategory category,
                                        PatternStatus status,
                                        PatternScope scope,
                                        String projectKey,
                                        int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from knowledge_pattern where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (category != null) {
            sql.append(" and category = :category");
            params.put("category", category.name());
        }
        if (status != null) {
            sql.append(" and status = :status");
            params.put("status", status.name());
        }
        if (scope != null) {
            sql.append(" and scope = :scope");
            params.put("scope", scope.name());
        }
        if (projectKey != null && !projectKey.isBlank()) {
            sql.append(" and project_key = :projectKey");
            params.put("projectKey", projectKey);
        }
        sql.append(" order by last_seen_at desc limit :limit");
        params.put("limit", Math.max(1, limit));
        return jdbc.sql(sql.toString()).params(params).query(PATTERN_MAPPER).list();
    }

    public long insert(KnowledgePattern p) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into knowledge_pattern
                          (fingerprint, category, scope, project_key, statement, anti_pattern, rationale,
                           tags, status, hit_count, confidence, first_seen_at, last_seen_at, author,
                           source_ref, superseded_by, archived_reason)
                        values
                          (:fingerprint, :category, :scope, :projectKey, :statement, :antiPattern, :rationale,
                           :tags, :status, :hitCount, :confidence, :firstSeenAt, :lastSeenAt, :author,
                           :sourceRef, :supersededBy, :archivedReason)
                        """)
                .params(toParams(p))
                .update(keyHolder);
        long id = extractId(keyHolder);
        p.setId(id);
        return id;
    }

    public void update(KnowledgePattern p) {
        Map<String, Object> params = toParams(p);
        params.put("id", p.getId());
        jdbc.sql("""
                        update knowledge_pattern set
                          fingerprint = :fingerprint, category = :category, scope = :scope,
                          project_key = :projectKey, statement = :statement, anti_pattern = :antiPattern,
                          rationale = :rationale, tags = :tags, status = :status, hit_count = :hitCount,
                          confidence = :confidence, first_seen_at = :firstSeenAt, last_seen_at = :lastSeenAt,
                          author = :author, source_ref = :sourceRef, superseded_by = :supersededBy,
                          archived_reason = :archivedReason
                        where id = :id
                        """)
                .params(params)
                .update();
    }

    public void insertEvidence(long patternId, PatternEvidence evidence) {
        jdbc.sql("""
                        insert into pattern_evidence (pattern_id, note, author, source_ref, similarity, recorded_at)
                        values (:patternId, :note, :author, :sourceRef, :similarity, :recordedAt)
                        """)
                .param("patternId", patternId)
                .param("note", truncate(evidence.note(), 3900))
                .param("author", truncate(evidence.author(), 120))
                .param("sourceRef", truncate(evidence.sourceRef(), 400))
                .param("similarity", evidence.similarity())
                .param("recordedAt", Timestamp.from(evidence.recordedAt()))
                .update();
    }

    public List<PatternEvidence> findEvidence(long patternId, int limit) {
        return jdbc.sql("""
                        select id, pattern_id, note, author, source_ref, similarity, recorded_at
                        from pattern_evidence where pattern_id = :patternId
                        order by recorded_at desc limit :limit
                        """)
                .param("patternId", patternId)
                .param("limit", Math.max(1, limit))
                .query(EVIDENCE_MAPPER)
                .list();
    }

    /** status -> count, used by knowledge_stats. */
    public Map<String, Long> countByStatus() {
        return counts("select status as k, count(*) as c from knowledge_pattern group by status");
    }

    /** category -> count of ACTIVE rules. */
    public Map<String, Long> countActiveByCategory() {
        return counts("select category as k, count(*) as c from knowledge_pattern where status = 'ACTIVE' group by category");
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql(sql).query().listOfRows().forEach(row -> out.put(
                String.valueOf(row.get("k")),
                ((Number) row.get("c")).longValue()));
        return out;
    }

    private static Map<String, Object> toParams(KnowledgePattern p) {
        Map<String, Object> params = new HashMap<>();
        params.put("fingerprint", p.getFingerprint());
        params.put("category", p.getCategory().name());
        params.put("scope", p.getScope().name());
        params.put("projectKey", p.getProjectKey());
        params.put("statement", truncate(p.getStatement(), 1900));
        params.put("antiPattern", truncate(p.getAntiPattern(), 1900));
        params.put("rationale", truncate(p.getRationale(), 1900));
        params.put("tags", truncate(p.getTags(), 400));
        params.put("status", p.getStatus().name());
        params.put("hitCount", p.getHitCount());
        params.put("confidence", p.getConfidence());
        params.put("firstSeenAt", Timestamp.from(p.getFirstSeenAt()));
        params.put("lastSeenAt", Timestamp.from(p.getLastSeenAt()));
        params.put("author", truncate(p.getAuthor(), 120));
        params.put("sourceRef", truncate(p.getSourceRef(), 400));
        params.put("supersededBy", p.getSupersededBy());
        params.put("archivedReason", truncate(p.getArchivedReason(), 400));
        return params;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static long extractId(GeneratedKeyHolder keyHolder) {
        List<Map<String, Object>> keys = new ArrayList<>(keyHolder.getKeyList());
        if (keys.isEmpty()) {
            throw new IllegalStateException("insert returned no generated key");
        }
        for (Object value : keys.get(0).values()) {
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        throw new IllegalStateException("generated key was not numeric: " + keys.get(0));
    }
}
