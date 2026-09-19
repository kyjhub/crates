-- 부하테스트가 만든 데이터 정리.
--
-- 테스트는 실제 API로 데이터를 만들기 때문에 정합성은 맞지만, 전부 난수 조합이라
-- 남겨두면 인기 보드 후보에 섞이고 이후 측정치를 오염시킨다.
--
-- 사용법:
--   docker exec -i -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server \
--     psql -U $POSTGRESQL_USERNAME -d crates -f - < load-test/cleanup.sql
--
-- 삭제 순서는 FK를 거스르지 않도록 자식 -> 부모다.

BEGIN;

CREATE TEMP TABLE loadtest_users ON COMMIT DROP AS
SELECT id FROM users WHERE login_id LIKE 'loadtest%';

CREATE TEMP TABLE loadtest_boards ON COMMIT DROP AS
SELECT id FROM board WHERE title LIKE 'loadtest%';

DELETE FROM board_feedback
 WHERE user_id IN (SELECT id FROM loadtest_users)
    OR board_id IN (SELECT id FROM loadtest_boards);

DELETE FROM board_item WHERE board_id IN (SELECT id FROM loadtest_boards);
DELETE FROM board      WHERE id       IN (SELECT id FROM loadtest_boards);
DELETE FROM user_vector WHERE user_id IN (SELECT id FROM loadtest_users);
DELETE FROM user_refresh_tokens WHERE user_id IN (SELECT id FROM loadtest_users);
DELETE FROM users WHERE id IN (SELECT id FROM loadtest_users);

-- 지운 뒤 남은 것이 초기 상태(시딩 보드 10건)인지 확인용
SELECT (SELECT count(*) FROM board) AS 보드,
       (SELECT count(*) FROM users) AS 사용자,
       (SELECT count(*) FROM board_feedback) AS 좋아요;

COMMIT;

-- 지운 공간을 실제로 돌려준다.
--
-- DELETE는 행을 죽은 것으로 표시할 뿐 페이지를 반납하지 않는다. 그래서 정리 직후에도
-- 테이블은 이전 실행의 크기를 유지하고, seq scan은 그 빈 페이지를 전부 읽는다.
-- 규모를 바꿔가며 재면 새 규모가 아니라 **이전 실행의 잔해**를 재게 된다.
--
--   보드 4만 개를 정리한 직후:  board 820행이 1,311페이지
--   VACUUM FULL 후:             board 820행이    15페이지      ← seq scan 비용 87배 차이
--
-- 일반 VACUUM으로는 부족하다. 빈 페이지를 재사용 가능으로만 표시하고 테이블 끝의
-- 연속된 빈 페이지만 잘라내므로, 살아있는 행 하나가 뒤쪽에 있으면 통째로 남는다.
--
-- ACCESS EXCLUSIVE 락을 잡는다. 앱이 떠 있으면 밀릴 수 있어 상한을 둔다.
-- 시간 초과는 조용히 넘기지 않는다 — 정리가 안 된 채 측정하면 위의 87배를 재게 된다.
SET lock_timeout = '30s';

VACUUM (FULL, ANALYZE) board;
VACUUM (FULL, ANALYZE) board_item;
VACUUM (FULL, ANALYZE) board_feedback;
