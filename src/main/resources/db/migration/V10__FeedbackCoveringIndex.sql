-- idx_feedback_user_recent 를 커버링 인덱스로 바꾼다.
--
-- 취향 벡터 재계산(findLikedBoardsWithTime)은 좋아요를 누를 때마다 돈다. 이 서비스에서
-- 가장 자주 실행되는 쓰기 경로다. 그런데 계정당 좋아요 1만 건에서 호출마다 10,113블록을 읽었다.
--
--   Hash Anti Join (rows=10000)                              Buffers: shared hit=10113
--     -> Bitmap Heap Scan on board_feedback (rows=10000)  Heap Blocks: exact=10000
--          -> Bitmap Index Scan on idx_feedback_user_recent               hit=112
--     -> Index Only Scan using idx_board_deleted (rows=0)                 hit=1
--
-- 인덱스 탐색은 112블록으로 끝난다. 삭제 보드 배제(V8)도 1블록이다. 남은 10,000블록은 전부
-- 힙 접근이다 — 쿼리가 board_id 를 돌려줘야 하는데 인덱스에 그 컬럼이 없어서, 행마다 테이블을
-- 찾아간다. 좋아요가 보드 전체에 흩어져 있어 한 블록에 한 행꼴이라 캐시도 듣지 않는다.
--
-- board_id 를 INCLUDE 로 얹으면 인덱스만으로 끝난다(Index Only Scan).
--
-- 키가 아니라 INCLUDE 인 이유: board_id 로 찾거나 정렬하는 쿼리는 없다. 결과에 실려 나갈 뿐이다.
-- 키에 넣으면 B-tree 내부 노드까지 커져 탐색이 느려지고, 정렬 순서에도 의미 없는 컬럼이 낀다.
-- INCLUDE 는 리프에만 저장되므로 딱 필요한 만큼만 커진다.
--
-- ── DROP 에 대하여 ──────────────────────────────────────────────────────────
-- 이 마이그레이션은 기존 인덱스를 지운다. 이 프로젝트의 규칙(추가/수정만, DROP 금지)에
-- 대한 예외로 두는 근거는 다음과 같다.
--
--   데이터가 사라지지 않는다. 인덱스는 파생물이고 언제든 다시 만들 수 있다.
--   새 인덱스의 키가 기존과 완전히 같다 (user_id, rating, created_at DESC, id DESC).
--   즉 기존 인덱스가 받치던 모든 쿼리를 새 인덱스가 그대로 받는다 — 진부분집합이 아니라 상위집합이다.
--   남겨두면 순손실이다. 10M 행에서 850MB를 더 쓰고, 좋아요를 누를 때마다 두 인덱스를 갱신한다.
--
-- 순서가 중요하다. 새 인덱스를 먼저 만든 뒤에 지운다. 반대로 하면 그 사이 들어온 쿼리가
-- 받쳐줄 인덱스 없이 풀스캔으로 떨어진다.
--
-- 이름은 되돌린다. 문서와 주석이 idx_feedback_user_recent 를 참조하고 있어, 이름이 바뀌면
-- 그 참조가 전부 끊긴다. 바뀐 것은 정의이지 역할이 아니다.
--
-- CONCURRENTLY 는 쓰지 않는다. Flyway 가 마이그레이션을 트랜잭션으로 감싸는데 CONCURRENTLY 는
-- 트랜잭션 안에서 돌 수 없다. 인덱스를 만드는 동안 board_feedback 에 쓰기 잠금이 걸린다.
--
-- 주의: Index Only Scan 은 가시성 맵에 기대므로, 대량 INSERT 직후처럼 VACUUM 이 밀려 있으면
-- Heap Fetches 가 남아 이득이 줄어든다. autovacuum 이 도는 정상 운영에서는 문제되지 않는다.

CREATE INDEX IF NOT EXISTS idx_feedback_user_recent_v2
    ON board_feedback (user_id, rating, created_at DESC, id DESC)
    INCLUDE (board_id);

DROP INDEX IF EXISTS idx_feedback_user_recent;

ALTER INDEX idx_feedback_user_recent_v2 RENAME TO idx_feedback_user_recent;
