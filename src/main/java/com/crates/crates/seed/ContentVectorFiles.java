package com.crates.crates.seed;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 도메인별 콘텐츠 벡터 CSV의 위치를 아는 유일한 곳.
 *
 * <p>RDB 시딩(V5~V7)과 Qdrant 적재(ContentVectorLoader)가 같은 파일을 봐야 한다.
 * 시딩은 "벡터가 있는 콘텐츠만 넣는다"를 이 파일의 id로 판단하고, 적재는 같은 파일의 벡터를
 * 넣는다. 두 쪽이 다른 파일을 보면 벡터 없는 콘텐츠가 생기거나 적재할 곳 없는 벡터가 남는다.</p>
 *
 * <p>경로는 Spring Resource 형식이라 {@code classpath:}와 {@code file:} 둘 다 받는다.
 * 운영에서는 jar에 싣지 않고 외부 경로를 넘기면 된다.</p>
 */
@Component
public class ContentVectorFiles {

    /** 적재 순서. V5~V7의 시딩 순서와 같게 두면 content.id 오름차순으로 적재된다. */
    public static final List<String> DTYPES = List.of("BOOK", "MOVIE", "MUSIC");

    private final ResourceLoader resourceLoader;
    private final String bookLocation;
    private final String movieLocation;
    private final String musicLocation;

    public ContentVectorFiles(
            ResourceLoader resourceLoader,
            @Value("${ai.vectorstore.qdrant.load.files.book}") String bookLocation,
            @Value("${ai.vectorstore.qdrant.load.files.movie}") String movieLocation,
            @Value("${ai.vectorstore.qdrant.load.files.music}") String musicLocation
    ) {
        this.resourceLoader = resourceLoader;
        this.bookLocation = bookLocation;
        this.movieLocation = movieLocation;
        this.musicLocation = musicLocation;
    }

    public Resource resourceFor(String dtype) {
        String location = switch (dtype) {
            case "BOOK" -> bookLocation;
            case "MOVIE" -> movieLocation;
            case "MUSIC" -> musicLocation;
            default -> throw new IllegalArgumentException("벡터 파일이 없는 콘텐츠 타입입니다: " + dtype);
        };

        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("콘텐츠 벡터 파일을 찾을 수 없습니다: " + location);
        }
        return resource;
    }

    /**
     * 벡터 CSV의 id만 임시 테이블 {@code tableName(id TEXT PRIMARY KEY)}에 올린다.
     *
     * <p>파일을 COPY로 통째로 올리면 base64 벡터까지(book만 440MB) 임시 테이블에 쓰게 된다.
     * 시딩에 필요한 건 "이 키에 벡터가 있는가"뿐이라 id 열만 골라 흘려보낸다.
     * PRIMARY KEY는 중복 id를 막고, 시딩의 NOT EXISTS 검사가 탈 인덱스를 겸한다.</p>
     *
     * @param connection 마이그레이션이 쥐고 있는 커넥션 (ON COMMIT DROP이 그 트랜잭션에 묶인다)
     */
    public void stageKeys(Connection connection, String dtype, String tableName) throws SQLException, IOException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TEMP TABLE " + tableName + " (id TEXT PRIMARY KEY) ON COMMIT DROP");
        }

        CopyIn copyIn = connection.unwrap(PGConnection.class)
                .getCopyAPI()
                .copyIn("COPY " + tableName + " (id) FROM STDIN WITH (FORMAT text)");
        try {
            ContentVectorCsv.forEachRow(resourceFor(dtype), row -> {
                byte[] line = (row.sourceKey() + "\n").getBytes(StandardCharsets.UTF_8);
                try {
                    copyIn.writeToCopy(line, 0, line.length);
                } catch (SQLException e) {
                    throw new IllegalStateException("벡터 id를 임시 테이블에 올리지 못했습니다: " + tableName, e);
                }
            });
            copyIn.endCopy();
        } finally {
            if (copyIn.isActive()) {
                copyIn.cancelCopy();
            }
        }
    }
}
