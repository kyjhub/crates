package com.crates.crates.seed;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * data/popular_top200_ids.csv(종류별 인기순 200개)로 가입 직후 취향 콘텐츠 후보(onboarding_content)를 채운다.
 *
 * <p>CSV의 id는 content.source_key와 같은 원본 데이터셋의 id다(예: book `gr_3.Harry_Potter...`, movie `816692`,
 * music Spotify track id).
 * 종류와 원본 id로 content를 찾아 짝짓는다. 순위는 CSV에 적힌 순서(종류마다 1부터)를 그대로 쓴다.</p>
 *
 * <p>content에 없는 id는 건너뛴다. 콘텐츠 시딩(V5~V7)이 벡터 없는 콘텐츠를 넣지 않아서 생긴다
 * (2026-09-28 기준 음악 5곡). 후보로 보여줘도 고르는 순간 "벡터 없음"으로 거절되므로 빼는 것이 맞다.
 * 몇 건을 건너뛰었는지는 로그로 남긴다.</p>
 */
@Slf4j
@Component
public class V9__SeedOnboardingContents extends BaseJavaMigration {

    private static final String CSV_RESOURCE = "/data/popular_top200_ids.csv";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TEMP TABLE onboarding_raw (
                        staging_row_id BIGSERIAL PRIMARY KEY,
                        domain TEXT,
                        source_key TEXT,
                        name TEXT
                    ) ON COMMIT DROP
                    """);
        }

        copyCsvIntoStaging(connection);

        try (Statement stmt = connection.createStatement()) {
            // 순위는 content와 짝짓기 전에 매긴다. 짝이 없는 행을 먼저 빼면 뒤의 순위가 당겨져
            // CSV가 말하는 인기 순위와 달라진다.
            stmt.execute("""
                    INSERT INTO onboarding_content (content_id, popularity_rank)
                    SELECT c.id, r.popularity_rank
                    FROM (
                        SELECT upper(btrim(domain)) AS dtype,
                               btrim(source_key) AS source_key,
                               row_number() OVER (PARTITION BY upper(btrim(domain)) ORDER BY staging_row_id) AS popularity_rank
                        FROM onboarding_raw
                    ) r
                    JOIN content c ON c.dtype = r.dtype AND c.source_key = r.source_key
                    """);

            try (ResultSet rs = stmt.executeQuery("""
                    SELECT upper(btrim(r.domain)), count(*) AS total, count(c.id) AS matched
                    FROM onboarding_raw r
                    LEFT JOIN content c ON c.dtype = upper(btrim(r.domain)) AND c.source_key = btrim(r.source_key)
                    GROUP BY 1 ORDER BY 1
                    """)) {
                while (rs.next()) {
                    long total = rs.getLong("total");
                    long matched = rs.getLong("matched");
                    if (matched < total) {
                        log.warn("가입 취향 후보 [{}] {}건 중 {}건은 content에 없어 건너뜁니다(벡터가 없어 시딩되지 않은 콘텐츠).",
                                rs.getString(1), total, total - matched);
                    }
                    log.info("가입 취향 후보 [{}] {}건", rs.getString(1), matched);
                }
            }
        }
    }

    private void copyCsvIntoStaging(Connection connection) throws Exception {
        CopyManager copyManager = connection.unwrap(PGConnection.class).getCopyAPI();

        try (InputStream csvStream = getClass().getResourceAsStream(CSV_RESOURCE)) {
            if (csvStream == null) {
                throw new IllegalStateException("클래스패스에서 popular_top200_ids.csv를 찾을 수 없습니다: " + CSV_RESOURCE);
            }
            copyManager.copyIn("""
                    COPY onboarding_raw (domain, source_key, name)
                    FROM STDIN WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
                    """, csvStream);
        }
    }
}
