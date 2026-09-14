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
DELETE FROM user_refresh_token WHERE user_id IN (SELECT id FROM loadtest_users);
DELETE FROM users WHERE id IN (SELECT id FROM loadtest_users);

-- 지운 뒤 남은 것이 초기 상태(시딩 보드 10건)인지 확인용
SELECT (SELECT count(*) FROM board) AS 보드,
       (SELECT count(*) FROM users) AS 사용자,
       (SELECT count(*) FROM board_feedback) AS 좋아요;

COMMIT;
