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
 * data/music.csv의 name/year/artists/lyrics를 뽑아
 * content/music 테이블에 적재한다.
 * year에서는 연도만 추출하고, artists의 JSON 배열 문자열은
 * PostgreSQL varchar 배열로 변환한다.
 * 원본에 모든 필드가 같은 중복 행(id 기준 354행)이 있어 하나만 남기고, 벡터 CSV에 없는 곡은 넣지 않는다.
 * music_id → content.id 짝을 시딩 전용 매핑 테이블(content_seed_key)에 남긴다.
 * s3ObjectKey/imageExtension은 임시 테이블이 살아있는 동안 ContentImageUploader가 채운다.
 */
@Component
@RequiredArgsConstructor
public class V3__SeedMusic extends BaseJavaMigration {

    private static final String CSV_RESOURCE = "/data/music.csv";

    /** 아직 staging_music_row_id가 살아있는 시점에만 img URL을 content.id와 짝지을 수 있다. */
    private static final String IMAGE_TARGET_SQL = """
            SELECT c.id, r.img
            FROM content c
            JOIN music_raw r ON r.staging_row_id = c.staging_music_row_id
            WHERE c.staging_music_row_id IS NOT NULL
              AND NULLIF(btrim(r.img), '') IS NOT NULL
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
                    CREATE TEMP TABLE music_raw (
                        staging_row_id BIGSERIAL PRIMARY KEY,
                        preview TEXT,
                        img TEXT,
                        acousticness_artist TEXT,
                        danceability_artist TEXT,
                        energy_artist TEXT,
                        instrumentalness_artist TEXT,
                        liveness_artist TEXT,
                        speechiness_artist TEXT,
                        valence_artist TEXT,
                        music_id TEXT,
                        album_name TEXT,
                        artists TEXT,
                        musical_key TEXT,
                        mode TEXT,
                        tempo TEXT,
                        duration_ms TEXT,
                        lyrics TEXT,
                        release_year_text TEXT,
                        genre TEXT,
                        popularity TEXT,
                        total_artist_followers TEXT,
                        avg_artist_popularity TEXT,
                        artist_ids TEXT,
                        niche_genres TEXT,
                        track_name TEXT,
                        danceability TEXT,
                        energy TEXT,
                        loudness TEXT,
                        speechiness TEXT,
                        acousticness TEXT,
                        instrumentalness TEXT,
                        liveness TEXT,
                        valence TEXT,
                        description TEXT,
                        query_text TEXT
                    ) ON COMMIT DROP
                    """);
        }

        copyCsvIntoStaging(connection);
        vectorFiles.stageKeys(connection, "MUSIC", "music_vector_key");

        try (Statement stmt = connection.createStatement()) {
            // 중복 제거. 같은 music_id의 행은 모든 필드가 같으므로 먼저 들어온 행만 남긴다.
            // 남겨두면 같은 벡터가 point 두 개에 붙어 유사 콘텐츠 검색에 같은 작품이 연달아 나온다.
            stmt.execute("""
                    DELETE FROM music_raw a
                    USING music_raw b
                    WHERE a.music_id = b.music_id
                      AND a.staging_row_id > b.staging_row_id
                    """);

            // 벡터가 없는 곡은 넣지 않는다.
            stmt.execute("""
                    DELETE FROM music_raw r
                    WHERE NOT EXISTS (SELECT 1 FROM music_vector_key v WHERE v.id = r.music_id)
                    """);

            // content <-> music 두 테이블에 나눠 넣어야 해서(JOINED 상속), 방금 생성된
            // content.id를 music_raw 행과 다시 짝지을 상관관계 키가 필요함.
            // 위에서 중복을 걸렀지만 짝짓기는 원래대로 임시 테이블의 행 ID로 한다.
            stmt.execute("ALTER TABLE content ADD COLUMN staging_music_row_id BIGINT");

            stmt.execute("""
                    INSERT INTO content (dtype, title, release_year, staging_music_row_id)
                    SELECT
                        'MUSIC',
                        NULLIF(btrim(track_name), ''),
                        CASE
                            WHEN btrim(release_year_text) ~ '^\\d{4}(\\.0+)?$'
                                THEN substring(btrim(release_year_text) from 1 for 4)::int
                            ELSE NULL
                        END,
                        staging_row_id
                    FROM music_raw
                    """);

            stmt.execute("""
                    INSERT INTO music (content_id, artist, plot)
                    SELECT
                        c.id,
                        CASE
                            WHEN NULLIF(btrim(r.artists), '') IS NULL THEN NULL
                            ELSE ARRAY(
                                SELECT jsonb_array_elements_text(r.artists::jsonb)
                            )::varchar[]
                        END,
                        NULLIF(btrim(r.lyrics), '')
                    FROM content c
                    JOIN music_raw r ON r.staging_row_id = c.staging_music_row_id
                    WHERE c.staging_music_row_id IS NOT NULL
                    """);

            imageUploader.seedImages(connection, IMAGE_TARGET_SQL, "music");

            // staging_music_row_id가 사라지기 전에 벡터 적재용 짝을 남긴다. 위에서 중복을 걸러내 music_id가 유일하다.
            stmt.execute("""
                    INSERT INTO content_seed_key (content_id, dtype, source_key)
                    SELECT c.id, 'MUSIC', r.music_id
                    FROM content c
                    JOIN music_raw r ON r.staging_row_id = c.staging_music_row_id
                    WHERE c.staging_music_row_id IS NOT NULL
                    """);

            stmt.execute("ALTER TABLE content DROP COLUMN staging_music_row_id");
        }
    }

    private void copyCsvIntoStaging(Connection connection) throws Exception {
        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        CopyManager copyManager = pgConnection.getCopyAPI();

        try (InputStream csvStream = getClass().getResourceAsStream(CSV_RESOURCE)) {
            if (csvStream == null) {
                throw new IllegalStateException("클래스패스에서 music.csv를 찾을 수 없습니다: " + CSV_RESOURCE);
            }
            copyManager.copyIn("""
                    COPY music_raw (
                        preview,
                        img,
                        acousticness_artist,
                        danceability_artist,
                        energy_artist,
                        instrumentalness_artist,
                        liveness_artist,
                        speechiness_artist,
                        valence_artist,
                        music_id,
                        album_name,
                        artists,
                        musical_key,
                        mode,
                        tempo,
                        duration_ms,
                        lyrics,
                        release_year_text,
                        genre,
                        popularity,
                        total_artist_followers,
                        avg_artist_popularity,
                        artist_ids,
                        niche_genres,
                        track_name,
                        danceability,
                        energy,
                        loudness,
                        speechiness,
                        acousticness,
                        instrumentalness,
                        liveness,
                        valence,
                        description,
                        query_text
                    ) FROM STDIN WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
                    """, csvStream);
        }
    }
}
