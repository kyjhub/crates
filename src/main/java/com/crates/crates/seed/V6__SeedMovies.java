package com.crates.crates.seed;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;

/**
 * data/movie.csv의 title/release_date/running_time/director/actor/text를 뽑아
 * content/movie 테이블에 적재한다.
 * release_date에서는 연도만 추출하고, director/actor의 JSON 배열 문자열은
 * PostgreSQL varchar 배열로 변환한다.
 * 원본에 모든 필드가 같은 중복 행(imdbId 기준 593행)이 있어 하나만 남기고, 벡터 CSV에 없는 영화는 넣지 않는다.
 * imdb_id를 content.source_key로 남긴다. ContentVectorLoader가 이 값으로 벡터를 content.id에 짝짓는다.
 * s3ObjectKey/imageExtension은 임시 테이블이 살아있는 동안 ContentImageUploader가 채운다.
 */
@Component
@RequiredArgsConstructor
public class V6__SeedMovies extends BaseJavaMigration {

    private static final String CSV_RESOURCE = "/data/movie.csv";

    /** movie_raw가 살아있는 이 트랜잭션 안에서만 poster URL을 content.id와 짝지을 수 있다. */
    private static final String IMAGE_TARGET_SQL = """
            SELECT c.id, r.poster
            FROM content c
            JOIN movie_raw r ON r.imdb_id = c.source_key
            WHERE c.dtype = 'MOVIE'
              AND NULLIF(btrim(r.poster), '') IS NOT NULL
            ORDER BY c.id
            LIMIT ?
            """;

    private final ContentImageUploader imageUploader;
    private final ContentVectorFiles vectorFiles;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TEMP TABLE movie_raw (
                        staging_row_id BIGSERIAL PRIMARY KEY,
                        imdb_id TEXT,
                        imdb_link TEXT,
                        title TEXT,
                        imdb_score TEXT,
                        genre TEXT,
                        poster TEXT,
                        plot_text TEXT,
                        query_text TEXT,
                        release_date TEXT,
                        running_time TEXT,
                        director TEXT,
                        actor TEXT
                    ) ON COMMIT DROP
                    """);
        }

        copyCsvIntoStaging(connection);
        vectorFiles.stageKeys(connection, "MOVIE", "movie_vector_key");

        try (Statement stmt = connection.createStatement()) {
            // 중복 제거. 같은 imdb_id의 행은 모든 필드가 같으므로 먼저 들어온 행만 남긴다.
            // 남겨두면 같은 벡터가 point 두 개에 붙어 유사 콘텐츠 검색에 같은 작품이 연달아 나온다.
            stmt.execute("""
                    DELETE FROM movie_raw a
                    USING movie_raw b
                    WHERE a.imdb_id = b.imdb_id
                      AND a.staging_row_id > b.staging_row_id
                    """);

            // 벡터가 없는 영화는 넣지 않는다.
            stmt.execute("""
                    DELETE FROM movie_raw r
                    WHERE NOT EXISTS (SELECT 1 FROM movie_vector_key v WHERE v.id = r.imdb_id)
                    """);

            // content <-> movie 두 테이블에 나눠 넣어야 해서(JOINED 상속), 방금 생성된
            // content.id를 movie_raw 행과 다시 짝지을 상관관계 키가 필요함.
            // 위에서 중복을 걸러냈으므로 imdb_id가 곧 유일한 source_key다.
            stmt.execute("""
                    INSERT INTO content (dtype, title, release_year, source_key)
                    SELECT
                        'MOVIE',
                        NULLIF(btrim(title), ''),
                        CASE
                            WHEN btrim(release_date) ~ '^\\d{4}'
                                THEN substring(btrim(release_date) from 1 for 4)::int
                            ELSE NULL
                        END,
                        imdb_id
                    FROM movie_raw
                    """);

            stmt.execute("""
                    INSERT INTO movie (content_id, running_time, director, actor, plot)
                    SELECT
                        c.id,
                        CASE
                            WHEN btrim(r.running_time) ~ '^\\d+(\\.0+)?$'
                                THEN btrim(r.running_time)::numeric::int
                            ELSE NULL
                        END,
                        CASE
                            WHEN NULLIF(btrim(r.director), '') IS NULL THEN NULL
                            ELSE ARRAY(
                                SELECT jsonb_array_elements_text(r.director::jsonb)
                            )::varchar[]
                        END,
                        CASE
                            WHEN NULLIF(btrim(r.actor), '') IS NULL THEN NULL
                            ELSE ARRAY(
                                SELECT jsonb_array_elements_text(r.actor::jsonb)
                            )::varchar[]
                        END,
                        NULLIF(btrim(r.plot_text), '')
                    FROM content c
                    JOIN movie_raw r ON r.imdb_id = c.source_key
                    WHERE c.dtype = 'MOVIE'
                    """);

            imageUploader.seedImages(connection, IMAGE_TARGET_SQL, "movie");

        }
    }

    private void copyCsvIntoStaging(Connection connection) throws Exception {
        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        CopyManager copyManager = pgConnection.getCopyAPI();

        try (InputStream csvStream = getClass().getResourceAsStream(CSV_RESOURCE)) {
            if (csvStream == null) {
                throw new IllegalStateException("클래스패스에서 movie.csv를 찾을 수 없습니다: " + CSV_RESOURCE);
            }
            copyManager.copyIn("""
                    COPY movie_raw (
                        imdb_id,
                        imdb_link,
                        title,
                        imdb_score,
                        genre,
                        poster,
                        plot_text,
                        query_text,
                        release_date,
                        running_time,
                        director,
                        actor
                    ) FROM STDIN WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
                    """, csvStream);
        }
    }
}
