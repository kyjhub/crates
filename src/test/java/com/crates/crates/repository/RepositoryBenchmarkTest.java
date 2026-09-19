package com.crates.crates.repository;

import com.crates.crates.enumData.Rating;
import com.crates.crates.enumData.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리포지토리 메서드 하나하나의 <b>DB 비용</b>을 재는 하네스.
 *
 * <p>k6(run-measure.sh)와 역할이 다르다. k6는 동시성 아래에서 end-to-end를 재고, 어느 쿼리가
 * 비싼지는 pg_stat_statements 상위권으로 <i>간접</i> 추적한다. 그 방식에는 구멍이 둘 있다.
 * 첫째, API 경로에 걸리지 않는 리포지토리 메서드는 통계에 행조차 생기지 않아 측정 대상에서
 * 통째로 빠진다. 둘째, psql에 SQL을 손으로 옮겨 적어 보완하면 Hibernate가 실제로 내보내는
 * SQL과 달라진다(enum을 리터럴로 쓰면 인덱스를 타지만 Hibernate는 바인드 파라미터로 보낸다 —
 * V5·V7에서 반복해서 물린 지점이다).</p>
 *
 * <p>여기서는 리포지토리 메서드를 <b>직접</b> 호출한다. 경로가 없어도 되고, 나가는 SQL은
 * 운영에서 나가는 것과 같다. 대신 단일 스레드라 커넥션 경합·락·비동기 재계산은 못 본다.
 * 둘은 보완 관계이지 대체 관계가 아니다.</p>
 *
 * <h2>데이터 준비</h2>
 * <pre>
 *   ./load-test/seed.sh 20 20000 180      # 계정 20 / 보드 2만 / 계정당 좋아요 180
 * </pre>
 * 준비 없이 돌리면 픽스처 단계에서 실패한다. <b>0행을 반환하는 쿼리를 재고 "빠르다"고
 * 결론 내리는 것</b>이 이 프로젝트에서 가장 자주 저지른 실수다.
 *
 * <h2>측정 방식</h2>
 * <p>{@code pg_stat_statements}에는 퍼센타일 컬럼이 없다(calls·total·min·max·mean·stddev뿐).
 * 그래서 <b>호출 한 번마다 {@code total_exec_time} 증분을 떠서</b> 표본을 모으고 p50/p95/p99를
 * 직접 계산한다. 이 값은 JDBC 왕복과 JPA 매핑을 제외한 <b>순수 DB 실행 시간</b>이다.
 * 그 둘을 보려고 Java wall-clock도 같이 잰다 — DB는 빠른데 wall-clock이 느리면 원인은
 * 엔티티 하이드레이션이나 N+1이다.</p>
 *
 * <p>통계를 리셋하지 않는다. 증분 방식이라 리셋이 필요 없고, 리셋하면 같은 DB를 보고 있는
 * 앱과 다른 측정의 누적치까지 날아간다.</p>
 */
@DataJpaTest(showSql = false)   // 로그 포매팅 비용이 측정 구간에 섞이지 않도록
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("benchmark")   // ddl-auto=none 을 고정한다. 빼면 개발 DB가 drop/create 된다.
class RepositoryBenchmarkTest {

    /**
     * 버리는 호출 횟수. 5를 넉넉히 넘겨야 한다 — pgjdbc의 {@code prepareThreshold} 기본값이 5라,
     * 5회째부터 서버사이드 prepared statement로 넘어가고 그때부터 PostgreSQL이 제네릭 플랜을
     * 고려하기 시작한다. 5회만 돌리면 우리가 계속 물렸던 그 영역에 닿기 직전에 멈춘다.
     */
    private static final int WARMUP = 30;

    /** 측정 표본 수. p99가 의미를 가지려면 최소 100은 필요하다. */
    private static final int ITERATIONS = 200;

    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    @Autowired private BoardFeedbackRepository boardFeedbackRepository;
    @Autowired private BoardRepository boardRepository;
    @Autowired private ContentRepository contentRepository;

    // ── 측정 대상 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[성능] findLikedBoardsWithTime — 취향 벡터 재계산이 좋아요 목록을 읽는 쿼리")
    void benchmark_findLikedBoardsWithTime() {
        Long userId = userWithMostLikes();
        benchmark("BoardFeedbackRepository.findLikedBoardsWithTime  (user_id=" + userId + ")",
                () -> boardFeedbackRepository.findLikedBoardsWithTime(userId, Rating.LIKE).size());
    }

    @Test
    @DisplayName("[성능] findLikedBoards — 보관함 좋아요 탭 (20건)")
    void benchmark_findLikedBoards() {
        Long userId = userWithMostLikes();
        benchmark("BoardFeedbackRepository.findLikedBoards  (user_id=" + userId + ", size=20)",
                () -> boardFeedbackRepository.findLikedBoards(userId, Rating.LIKE, PageRequest.of(0, 20)).size());
    }

    @Test
    @DisplayName("[성능] findPopularBoards — 인기 보드 (10건)")
    void benchmark_findPopularBoards() {
        requireRows("SELECT count(*) FROM board WHERE visibility = 'PUBLIC' AND deleted_at IS NULL",
                "공개 보드가 없습니다");
        benchmark("BoardRepository.findPopularBoards  (PUBLIC, size=10)",
                () -> boardRepository.findPopularBoards(Visibility.PUBLIC, PageRequest.of(0, 10)).size());
    }

    @Test
    @DisplayName("[성능] findContentIdsAfter — 콘텐츠 ID 커서 페이징 (256건)")
    void benchmark_findContentIdsAfter() {
        requireRows("SELECT count(*) FROM content", "콘텐츠가 없습니다");
        benchmark("ContentRepository.findContentIdsAfter  (after=0, size=256)",
                () -> contentRepository.findContentIdsAfter(0L, PageRequest.of(0, 256)).size());
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    /**
     * 좋아요를 가장 많이 가진 부하테스트 계정. 하드코딩한 id를 쓰면 안 된다 —
     * seed.sh는 매번 새 계정을 만들고 cleanup.sql이 지우므로 id가 고정되지 않는다.
     * (실제로 {@code user_id = 1L}로 박혀 있었고, 그 사용자는 존재하지 않아 0행짜리 쿼리를 재고 있었다.)
     */
    private Long userWithMostLikes() {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT f.user_id
                  FROM board_feedback f
                  JOIN users u ON u.id = f.user_id
                 WHERE u.login_id LIKE 'loadtest_u%' AND f.rating = 'LIKE'
                 GROUP BY f.user_id
                 ORDER BY count(*) DESC, f.user_id
                 LIMIT 1
                """, Long.class);

        assertThat(ids)
                .as("좋아요를 가진 loadtest 계정이 없습니다. "
                        + "먼저 ./load-test/seed.sh <계정수> <보드수> <계정당좋아요> 를 실행하세요")
                .isNotEmpty();
        return ids.get(0);
    }

    private void requireRows(String countSql, String message) {
        Long n = jdbcTemplate.queryForObject(countSql, Long.class);
        assertThat(n).as(message + " — ./load-test/seed.sh 를 먼저 실행하세요").isNotNull().isPositive();
    }

    // ── 하네스 ──────────────────────────────────────────────────────────────

    /** 한 스냅샷: [호출 수, DB 실행 시간(ms), 읽은 블록 수]. */
    private static final int CALLS = 0, EXEC_MS = 1, BLOCKS = 2;

    private void benchmark(String label, IntSupplier call) {
        // 1) 워밍업. 버린다. prepared statement 전환과 버퍼 캐시 적재가 여기서 끝난다.
        int rows = 0;
        for (int i = 0; i < WARMUP; i++) {
            entityManager.clear();
            rows = call.getAsInt();
        }

        assertThat(rows)
                .as("%s 가 0행을 반환합니다. 데이터가 없는 쿼리를 재면 '빠르다'는 결론만 나옵니다", label)
                .isPositive();

        // 2) 이 메서드가 어떤 queryid를 쓰는지 한 번 호출해서 알아낸다.
        //    (페이징처럼 한 번의 호출이 쿼리 두 개를 내보내는 경우가 있다.)
        Map<Long, double[]> before = snapshotAll();
        entityManager.clear();
        call.getAsInt();
        Map<Long, double[]> after = snapshotAll();

        List<Long> targets = after.keySet().stream()
                .filter(id -> after.get(id)[CALLS] > before.getOrDefault(id, new double[3])[CALLS])
                .collect(Collectors.toList());

        assertThat(targets)
                .as("pg_stat_statements가 이 호출의 쿼리를 잡지 못했습니다. "
                        + "확장이 설치되어 있는지(CREATE EXTENSION pg_stat_statements) 확인하세요")
                .isNotEmpty();

        int callsPerInvocation = (int) Math.round(targets.stream()
                .mapToDouble(id -> after.get(id)[CALLS] - before.getOrDefault(id, new double[3])[CALLS])
                .sum());

        // 3) 측정. 호출마다 증분을 떠서 표본을 만든다.
        List<Double> dbMs = new ArrayList<>(ITERATIONS);
        List<Double> javaMs = new ArrayList<>(ITERATIONS);
        double blocks = 0;
        int contaminated = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            // 1차 캐시가 남아 있으면 두 번째 호출부터 하이드레이션이 공짜가 되어
            // wall-clock이 실제보다 빠르게 나온다.
            entityManager.clear();

            double[] s0 = snapshot(targets);
            long t0 = System.nanoTime();
            call.getAsInt();
            long t1 = System.nanoTime();
            double[] s1 = snapshot(targets);

            // 같은 DB를 앱이 함께 쓰고 있으면 증분에 남의 호출이 섞인다. 호출 수가 예상과
            // 다르면 그 표본은 버린다 — 섞인 값을 평균에 넣느니 몇 개 잃는 편이 낫다.
            if (Math.round(s1[CALLS] - s0[CALLS]) != callsPerInvocation) {
                contaminated++;
                continue;
            }
            dbMs.add(s1[EXEC_MS] - s0[EXEC_MS]);
            javaMs.add((t1 - t0) / 1_000_000.0);
            blocks += s1[BLOCKS] - s0[BLOCKS];
        }

        assertThat(dbMs)
                .as("쓸 수 있는 표본이 없습니다(오염 %d건). 앱이 같은 쿼리를 동시에 호출하고 있는지 확인하세요",
                        contaminated)
                .isNotEmpty();

        report(label, rows, callsPerInvocation, dbMs, javaMs, blocks, contaminated, targets);
    }

    /** 전체 통계 스냅샷. 자기 자신(pg_stat_statements를 읽는 쿼리)은 뺀다. */
    private Map<Long, double[]> snapshotAll() {
        Map<Long, double[]> map = new HashMap<>();
        jdbcTemplate.query("""
                SELECT queryid, calls, total_exec_time, shared_blks_hit + shared_blks_read AS blks
                  FROM pg_stat_statements
                 WHERE queryid IS NOT NULL
                   AND query NOT LIKE '%pg_stat_statements%'
                """, rs -> {
            map.put(rs.getLong("queryid"),
                    new double[]{rs.getDouble("calls"), rs.getDouble("total_exec_time"), rs.getDouble("blks")});
        });
        return map;
    }

    /** 대상 queryid들의 누적 합. 호출마다 두 번 부르므로 좁게 조회한다. */
    private double[] snapshot(List<Long> queryIds) {
        String ids = queryIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(calls), 0)                              AS calls,
                       coalesce(sum(total_exec_time), 0)                    AS exec_ms,
                       coalesce(sum(shared_blks_hit + shared_blks_read), 0) AS blks
                  FROM pg_stat_statements
                 WHERE queryid IN (%s)
                """.formatted(ids),
                (rs, n) -> new double[]{rs.getDouble("calls"), rs.getDouble("exec_ms"), rs.getDouble("blks")});
    }

    // ── 출력 ────────────────────────────────────────────────────────────────

    private void report(String label, int rows, int callsPerInvocation,
                        List<Double> dbMs, List<Double> javaMs, double blocks,
                        int contaminated, List<Long> targets) {

        double[] db = sorted(dbMs);
        double[] java = sorted(javaMs);
        int n = db.length;

        String line = "─".repeat(96);
        System.out.println("\n" + line);
        System.out.printf("📊 %s%n", label);
        System.out.println(line);
        System.out.printf("   표본 %d회 (워밍업 %d회 버림%s)   반환 %d행   호출당 쿼리 %d개   호출당 블록 %.1f%n",
                n, WARMUP,
                contaminated > 0 ? ", 오염 " + contaminated + "회 버림" : "",
                rows, callsPerInvocation, blocks / n);
        System.out.println();
        System.out.printf("   %-14s %9s %9s %9s %9s %9s %9s%n", "", "p50", "p95", "p99", "max", "평균", "min");
        System.out.printf("   %-14s %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳%n", "DB 실행",
                pct(db, 50), pct(db, 95), pct(db, 99), db[n - 1], mean(db), db[0]);
        System.out.printf("   %-14s %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳ %8.3f㎳%n", "Java 왕복",
                pct(java, 50), pct(java, 95), pct(java, 99), java[n - 1], mean(java), java[0]);
        System.out.printf("   %-14s %8.3f㎳ %8.3f㎳ %8.3f㎳%n", "└ 차이(JPA+JDBC)",
                pct(java, 50) - pct(db, 50), pct(java, 95) - pct(db, 95), pct(java, 99) - pct(db, 99));

        System.out.println("\n   실행된 SQL (EXPLAIN 으로 계획을 보려면 이 텍스트를 쓸 것):");
        for (Long id : targets) {
            String sql = jdbcTemplate.queryForObject(
                    "SELECT left(regexp_replace(query, '\\s+', ' ', 'g'), 300) FROM pg_stat_statements WHERE queryid = ?",
                    String.class, id);
            System.out.printf("     [%d] %s%n", id, sql);
        }
        System.out.println(line);
        System.out.println("""
                   읽는 법
                     · DB 실행 = 순수 DB 시간. 왕복·JPA 매핑이 빠져 있다.
                     · 차이가 크면 병목은 쿼리가 아니라 엔티티 하이드레이션이나 N+1이다.
                     · 단일 스레드 · 버퍼 캐시 적재 후 값이다. 운영 지연이 아니라 '쿼리 자체의 비용'이다.
                     · 왜 비싼지는 여기서 안 나온다. 위 SQL을 EXPLAIN (ANALYZE, BUFFERS) 로 볼 것.
                """);
    }

    private static double[] sorted(List<Double> values) {
        double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a;
    }

    /** nearest-rank 퍼센타일. 표본이 적을 때 보간법보다 해석이 단순하다. */
    private static double pct(double[] sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.min(Math.max(rank, 1), sorted.length) - 1];
    }

    private static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(Double.NaN);
    }
}
