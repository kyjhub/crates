package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;

/**
 * data/music.csv의 name/year/artists/lyrics를 뽑아
 * content/music 테이블에 적재한다.
 * year에서는 연도만 추출하고, artists의 JSON 배열 문자열은
 * PostgreSQL varchar 배열로 변환한다.
 */
public class V3__SeedMusic extends BaseJavaMigration {

    private static final String CSV_RESOURCE = "/data/music.csv";

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

        try (Statement stmt = connection.createStatement()) {
            // content <-> music 두 테이블에 나눠 넣어야 해서(JOINED 상속), 방금 생성된
            // content.id를 music_raw 행과 다시 짝지을 상관관계 키가 필요함.
            // music_id는 CSV에서 중복될 수 있으므로 임시 테이블의 행 ID를 사용한다.
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
