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
 * data/book.csv(111,094행)의 author/description_clean/title/publishedDate/imgUrl만 뽑아
 * content/book 테이블에 적재한다.
 * publisher는 원본 데이터에 없어 NULL.
 * s3ObjectKey/imageExtension은 임시 테이블이 살아있는 동안 ContentImageUploader가 채운다.
 */
@Component
@RequiredArgsConstructor
public class V1__SeedBooks extends BaseJavaMigration {

    private static final String CSV_RESOURCE = "/data/book.csv";

    /** 아직 staging_asin이 살아있는 시점에만 원본 이미지 URL을 content.id와 짝지을 수 있다. */
    private static final String IMAGE_TARGET_SQL = """
            SELECT c.id, r.img_url
            FROM content c
            JOIN book_raw r ON r.asin = c.staging_asin
            WHERE c.staging_asin IS NOT NULL
              AND NULLIF(btrim(r.img_url), '') IS NOT NULL
            ORDER BY c.id
            LIMIT ?
            """;

    private final ContentImageUploader imageUploader;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TEMP TABLE book_raw (
                        asin TEXT,
                        title TEXT,
                        author TEXT,
                        category_name TEXT,
                        description TEXT,
                        description_clean TEXT,
                        img_url TEXT,
                        published_date TEXT,
                        query_text TEXT,
                        isbn TEXT,
                        source TEXT
                    ) ON COMMIT DROP
                    """);
        }

        copyCsvIntoStaging(connection);

        try (Statement stmt = connection.createStatement()) {
            // content <-> book 두 테이블에 나눠 넣어야 해서(JOINED 상속), 방금 생성된
            // content.id를 book_raw 행과 다시 짝지을 상관관계 키가 필요함 -> asin은 전부 유일하므로 사용
            stmt.execute("ALTER TABLE content ADD COLUMN staging_asin TEXT");

            stmt.execute("""
                    INSERT INTO content (dtype, title, release_year, staging_asin)
                    SELECT
                        'BOOK',
                        title,
                        CASE
                            WHEN btrim(published_date) ~ '^\\d{4}-\\d{2}-\\d{2}$' THEN substring(btrim(published_date) from 1 for 4)::int
                            WHEN btrim(published_date) ~ '^\\d{4}$' THEN btrim(published_date)::int
                            ELSE NULL
                        END,
                        asin
                    FROM book_raw
                    """);

            stmt.execute("""
                    INSERT INTO book (content_id, author, publisher, plot)
                    SELECT c.id, NULLIF(btrim(r.author), ''), NULL, NULLIF(btrim(r.description_clean), '')
                    FROM content c
                    JOIN book_raw r ON r.asin = c.staging_asin
                    WHERE c.staging_asin IS NOT NULL
                    """);

            // image_extension은 URL 경로로 추측하지 않고, 실제 응답 Content-Type을 보고
            // s3_object_key와 함께 채운다. 둘 중 하나만 채워진 상태가 생기지 않게 하기 위함.
            imageUploader.seedImages(connection, IMAGE_TARGET_SQL, "book");

            stmt.execute("ALTER TABLE content DROP COLUMN staging_asin");
        }
    }

    private void copyCsvIntoStaging(Connection connection) throws Exception {
        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        CopyManager copyManager = pgConnection.getCopyAPI();

        try (InputStream csvStream = getClass().getResourceAsStream(CSV_RESOURCE)) {
            if (csvStream == null) {
                throw new IllegalStateException("클래스패스에서 book.csv를 찾을 수 없습니다: " + CSV_RESOURCE);
            }
            copyManager.copyIn("COPY book_raw FROM STDIN WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')", csvStream);
        }
    }
}
