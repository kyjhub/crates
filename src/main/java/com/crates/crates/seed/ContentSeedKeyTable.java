package com.crates.crates.seed;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 시딩 전용 매핑 테이블 {@code content_seed_key(content_id, dtype, source_key)}.
 *
 * <p>벡터 CSV의 id는 원본 데이터셋의 id(book=asin, movie=imdbId, music=Spotify track id)라서
 * Qdrant에 넣으려면 content.id로 바꿔야 한다. 그 짝은 V1~V3가 content를 넣는 순간에만 알 수 있는데,
 * 벡터 적재(ContentVectorLoader)는 마이그레이션이 끝난 뒤에 돈다. 그 사이를 이 테이블이 잇는다.</p>
 *
 * <p>애플리케이션은 이 테이블을 모른다. 엔티티도 아니고, 콘텐츠와 벡터는 여전히 content.id 하나로만
 * 연결된다. 로더가 적재를 마치면 {@link #drop()}으로 지운다.</p>
 *
 * <p>FK는 걸지 않는다. V1~V3가 방금 넣은 content.id만 담고 적재가 끝나면 사라지는 테이블이다.
 * 적재가 실패하면 남는데, 그러면 다음 기동의 로더가 이 테이블로 다시 적재하고 지운다.
 * {@code docker compose down -v}로 처음부터 시작하면 DB와 함께 사라진다.</p>
 */
@Component
@RequiredArgsConstructor
public class ContentSeedKeyTable {

    public static final String TABLE = "content_seed_key";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /**
     * V1이 부른다. 가장 먼저 도는 시딩이라 V2·V3는 만들어진 테이블에 쓰기만 한다.
     * 엔티티가 아니라서 Hibernate가 만들어주지 않는다.
     */
    public static void create(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE %s (
                        content_id BIGINT PRIMARY KEY,
                        dtype      VARCHAR(31) NOT NULL,
                        source_key TEXT NOT NULL,
                        UNIQUE (dtype, source_key)
                    )
                    """.formatted(TABLE));
        }
    }

    public boolean exists() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, TABLE));
    }

    /** source_key → content.id. 없는 키는 결과에서 빠진다. (dtype, source_key) 유니크 인덱스를 탄다. */
    public Map<String, Long> findContentIds(String dtype, Collection<String> sourceKeys) {
        Map<String, Long> contentIdBySourceKey = new HashMap<>(sourceKeys.size());
        namedJdbcTemplate.query(
                "SELECT source_key, content_id FROM " + TABLE + " WHERE dtype = :dtype AND source_key IN (:keys)",
                new MapSqlParameterSource().addValue("dtype", dtype).addValue("keys", sourceKeys),
                rs -> {
                    contentIdBySourceKey.put(rs.getString(1), rs.getLong(2));
                }
        );
        return contentIdBySourceKey;
    }

    /** 적재가 끝나면 지운다. 이후 벡터만 다시 적재하려면 RDB부터 다시 시딩해야 한다. */
    public void drop() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TABLE);
    }
}
