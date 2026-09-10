-- 보관함(좋아요 탭) 조회 인덱스.
--
-- board_feedback에 걸려 있던 인덱스는 uk_board_feedback_board_user (board_id, user_id) 하나뿐이었다.
-- 선두 컬럼이 board_id라, "내가 좋아요한 보드"처럼 user_id만 조건으로 주는 조회는 이 인덱스로
-- 탐색할 수 없어 테이블을 통째로 훑고 정렬까지 하게 된다.
-- (PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들어주지 않는다. user_id는 FK지만 맨몸이었다.)
--
-- user_id와 rating이 등치 조건이므로 그 안에서 행이 이미 created_at DESC 순으로 나온다.
-- 정렬 연산이 사라지고 LIMIT이 앞부분만 읽고 멈춘다. idx_board_popular가 인기 보드에서
-- 하는 일과 같다.
--
-- rating을 인덱스에 포함시킨 이유:
--   부분 인덱스(WHERE rating = 'LIKE')가 더 작아 보이지만, Hibernate는 rating을 바인드
--   파라미터로 넘긴다. PostgreSQL이 제네릭 플랜을 쓰면 rating = $1이 rating = 'LIKE'를
--   함의한다고 증명하지 못해 부분 인덱스를 못 쓴다. 실행 횟수에 따라 플랜이 달라지는,
--   재현하기 어려운 종류의 문제라 컬럼을 그냥 포함시킨다. 크기 차이도 미미하다.
--
-- id를 마지막에 둔 이유:
--   동률 정렬 기준이 f.id DESC라, 여기까지 인덱스에 있어야 정렬이 완전히 인덱스로 해결된다.
--
-- 항상 빈 DB에서 시작하므로(docker compose down -v) DROP 없이 추가만 한다.

CREATE INDEX idx_feedback_user_recent
    ON board_feedback (user_id, rating, created_at DESC, id DESC);
