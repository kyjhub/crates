package com.crates.crates.seed;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 프론트 개발/API 테스트용 보드를 만든다.
 *
 * <p>실제 서비스의 보드는 사용자 벡터 또는 검색어 벡터와 가까운 콘텐츠를 모아 생성되므로
 * 타입별 구성 비율이 정해져 있지 않다. 그래서 여기서도 dtype을 섞어서 담고,
 * <b>보드 하나당 콘텐츠 8건</b>만 고정으로 맞춘다.
 *
 * <p>썸네일이 비어 보이지 않도록 이미지가 실제로 올라간 콘텐츠를 우선 사용한다.
 * (seed.image-upload.limit이 0이라 업로드된 이미지가 없으면 전체 콘텐츠로 폴백)
 */
@Slf4j
@Component
public class V4__SeedBoards extends BaseJavaMigration {

    /** 보드 하나에 담기는 콘텐츠 개수. 서비스 규칙상 고정값. */
    private static final int ITEMS_PER_BOARD = 8;

    /**
     * 생성할 보드 목록. 행 수만큼 보드가 만들어진다.
     * 제목은 실제 생성 경로(사용자 벡터 추천 / 검색어 벡터 유사)를 흉내 내서,
     * 프론트가 두 종류의 문자열을 모두 확인할 수 있게 섞어두었다.
     * like_count를 서로 다르게 준 이유는 /api/boards/liked가 like_count DESC로 정렬하기 때문.
     */
    /**
     * PRE_MADE는 소유자가 없는 전역 공용 보드라 user_id는 NULL(= 컬럼 자체를 넣지 않음)이고
     * visibility는 항상 PUBLIC이다. V5__BoardConstraints의 ck_board_owner / ck_board_visibility가
     * 이 규칙을 검증한다.
     *
     * <p>content_signature는 어떤 콘텐츠 8건이 담길지 확정된 뒤에야 계산할 수 있어서
     * 여기서는 빈 문자열로 두고, board_item을 넣은 다음 UPDATE로 채운다.</p>
     */
    private static final String BOARD_VALUES = """
            ('PRE_MADE', 'PUBLIC', '회원님을 위한 추천', '', 152, now()),
            ('PRE_MADE', 'PUBLIC', '''SF 스릴러''와 비슷한 콘텐츠', '', 137, now()),
            ('PRE_MADE', 'PUBLIC', '비 오는 날 어울리는', '', 121, now()),
            ('PRE_MADE', 'PUBLIC', '''재즈''와 비슷한 콘텐츠', '', 98, now()),
            ('PRE_MADE', 'PUBLIC', '요즘 많이 담긴', '', 86, now()),
            ('PRE_MADE', 'PUBLIC', '''90년대 감성''과 비슷한 콘텐츠', '', 74, now()),
            ('PRE_MADE', 'PUBLIC', '취향이 비슷한 사람들이 본', '', 61, now()),
            ('PRE_MADE', 'PUBLIC', '''몰입감 있는''과 비슷한 콘텐츠', '', 47, now()),
            ('PRE_MADE', 'PUBLIC', '새벽에 어울리는', '', 33, now()),
            ('PRE_MADE', 'PUBLIC', '''첫 장편 소설''과 비슷한 콘텐츠', '', 12, now())
            """;

    /**
     * board_item이 다 들어간 뒤 content_signature를 채운다.
     *
     * <p>정렬한 content_id를 콤마로 잇는 규칙은 Board.signatureOf()와 반드시 같아야 한다.
     * 규칙이 어긋나면 애플리케이션이 "같은 구성의 보드"를 찾지 못해 중복 저장이 다시 생긴다.</p>
     */
    private static final String UPDATE_SIGNATURE_SQL = """
            UPDATE board b
               SET content_signature = s.signature
              FROM (
                    SELECT board_id,
                           string_agg(content_id::text, ',' ORDER BY content_id) AS signature
                      FROM board_item
                     GROUP BY board_id
                   ) s
             WHERE b.id = s.board_id
               AND b.content_signature = ''
            """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        String contentFilter = resolveContentFilter(connection);

        // 데이터 수정 CTE로 보드를 넣고, RETURNING된 id에 0부터 순번(k)을 매긴 뒤
        // k번째 보드가 콘텐츠 목록의 [k*8, k*8+8) 구간을 가져가도록 한다.
        // 콘텐츠가 8*보드수보다 적으면 모듈로로 앞에서부터 다시 돌려쓴다.
        String sql = """
                WITH by_type AS (
                    SELECT id, dtype,
                           row_number() OVER (PARTITION BY dtype ORDER BY id) AS seq_in_type
                    FROM content
                    %s
                ),
                pool AS (
                    -- 타입별 n번째끼리 묶어 정렬하면 BOOK/MOVIE/MUSIC이 번갈아 나온다.
                    -- 그냥 dtype으로 정렬하면 보드가 단일 타입으로만 채워진다.
                    SELECT id, row_number() OVER (ORDER BY seq_in_type, dtype) - 1 AS rn
                    FROM by_type
                ),
                pool_size AS (
                    SELECT count(*) AS n FROM pool
                ),
                new_boards AS (
                    INSERT INTO board (board_type, visibility, title, content_signature, like_count, created_at)
                    VALUES %s
                    RETURNING id
                ),
                numbered AS (
                    SELECT id, row_number() OVER (ORDER BY id) - 1 AS k FROM new_boards
                )
                INSERT INTO board_item (board_id, content_id, slot_no)
                -- offset_in_board가 0부터 시작하므로 +1 해서 slot 1~8로 만든다.
                -- 이 순서가 곧 화면의 2행 4열 배치가 된다.
                SELECT b.id, p.id, s.offset_in_board + 1
                FROM numbered b
                CROSS JOIN generate_series(0, %d) AS s(offset_in_board)
                CROSS JOIN pool_size ps
                JOIN pool p ON p.rn = ((b.k * %d + s.offset_in_board) %% ps.n)
                """.formatted(
                contentFilter,
                BOARD_VALUES.strip(),
                ITEMS_PER_BOARD - 1,
                ITEMS_PER_BOARD);

        try (Statement stmt = connection.createStatement()) {
            int inserted = stmt.executeUpdate(sql);
            log.info("[board] 보드 시딩 완료: 보드 {}개, 아이템 {}건 (보드당 {}건)",
                    inserted / ITEMS_PER_BOARD, inserted, ITEMS_PER_BOARD);
        }

        // 콘텐츠가 확정된 뒤에야 계산할 수 있으므로 별도 문장으로 채운다.
        try (Statement stmt = connection.createStatement()) {
            int updated = stmt.executeUpdate(UPDATE_SIGNATURE_SQL);
            log.info("[board] content_signature 채움: {}개 보드", updated);
        }
    }

    /**
     * 이미지가 올라간 콘텐츠가 보드 하나를 채울 만큼 있으면 그것만 쓰고,
     * 아니면 전체 콘텐츠로 폴백한다. 콘텐츠가 아예 없으면 나눗셈에서 0으로 나누게 되므로 예외.
     */
    private String resolveContentFilter(Connection connection) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT count(*) FILTER (WHERE s3object_key IS NOT NULL) AS with_image,
                            count(*) AS total
                     FROM content
                     """)) {
            rs.next();
            long withImage = rs.getLong("with_image");
            long total = rs.getLong("total");

            if (total == 0) {
                throw new IllegalStateException(
                        "content가 비어 있어 보드를 만들 수 없습니다. V1~V3가 먼저 실행돼야 합니다.");
            }
            if (withImage >= ITEMS_PER_BOARD) {
                log.info("[board] 이미지가 있는 콘텐츠 {}건에서 보드를 구성합니다.", withImage);
                return "WHERE s3object_key IS NOT NULL";
            }
            log.warn("[board] 이미지가 있는 콘텐츠가 {}건뿐이라 전체 콘텐츠({}건)로 보드를 구성합니다. "
                    + "썸네일이 비어 보일 수 있습니다.", withImage, total);
            return "";
        }
    }
}
