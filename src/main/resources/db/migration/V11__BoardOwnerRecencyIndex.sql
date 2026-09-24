-- idx_board_owner 를 "소유자 + 최신순" 인덱스로 교체한다.
--
-- 기존: idx_board_owner (user_id, board_type) WHERE deleted_at IS NULL
--
-- 두 번째 컬럼이 아무것도 걸러내지 못한다. ck_board_owner 가
--
--     CHECK ((board_type = 'USER_CUSTOM') = (user_id IS NOT NULL))
--
-- 를 강제하므로, user_id 로 좁히는 순간 board_type 은 이미 USER_CUSTOM 으로 확정이다.
-- 그러면서 정작 필요한 created_at 순서는 갖고 있지 않아, 소유자 보드를 전부 읽고 정렬해야 했다.
--
-- board.user_id 로 좁히는 쿼리는 셋뿐이고 전부 최신순으로 정렬하거나(둘) 다른 인덱스를 탄다(하나).
--
--   findCreatedByMe          user_id + board_type, ORDER BY created_at DESC, id DESC
--   findMyBoards 갈래 ②      user_id,              ORDER BY created_at DESC, id DESC
--   findActiveUserBoardBySignature   → idx_board_signature_lookup 을 탄다 (무관)
--
-- 실측 (내가 만든 보드 20,000개, 같은 트랜잭션에서 세 구성을 비교)
--
--                              현재        둘 다      교체
--   findCreatedByMe          389블록      4블록      4블록
--                            1.984ms     0.013ms    0.011ms
--   findMyBoards 갈래 ②     3,635블록     77블록     77블록
--                            7.011ms     0.034ms    0.022ms
--
-- "둘 다"와 "교체"가 같다. 두 인덱스가 함께 있을 때도 플래너는 새 인덱스만 골랐다 —
-- 기존 인덱스는 이미 잉여였다는 뜻이다.
--
-- DROP 에 대하여. V10 과 같은 예외다. 데이터가 사라지지 않고(인덱스는 파생물이다), 지우는 쪽이
-- 받치던 쿼리를 남는 쪽이 전부 더 잘 받는다는 것을 위 표로 확인했다. 새 인덱스를 먼저 만든 뒤에
-- 지운다 — 반대로 하면 그 사이 들어온 쿼리가 받쳐줄 인덱스 없이 떨어진다.
--
-- 인덱스 개수가 그대로라(교체이지 추가가 아니다) 보드를 만들고 지울 때의 쓰기 비용은 늘지 않는다.
-- 크기는 592 kB -> 1,616 kB 로 1 MB 늘어난다(보드 20,000개 기준).
--
-- CONCURRENTLY 는 Flyway 의 트랜잭션 안에서 쓸 수 없다. 만드는 동안 board 에 쓰기 잠금이 걸린다.

CREATE INDEX IF NOT EXISTS idx_board_owner_recent
    ON board (user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

DROP INDEX IF EXISTS idx_board_owner;
