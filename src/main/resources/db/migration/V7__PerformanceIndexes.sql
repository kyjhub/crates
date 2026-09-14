-- 조회 성능 인덱스.
--
-- 두 인덱스가 같은 함정에서 나왔다. 바로 아래 "왜 조건 컬럼을 술어가 아니라 컬럼에 두는가"가
-- 둘 모두의 근거이므로 한 마이그레이션에 묶는다.
--
-- 항상 빈 DB에서 시작하므로(docker compose down -v) DROP 없이 추가만 한다.


-- ─────────────────────────────────────────────────────────────
-- 공통 근거 — 부분 인덱스의 술어에 바인드 파라미터를 두면 안 된다
-- ─────────────────────────────────────────────────────────────
--
-- PostgreSQL은 실행 계획을 캐시하면서 처음 몇 번은 커스텀 플랜(값을 아는 상태)을 쓰다가
-- 제네릭 플랜(값을 모르는 상태)으로 넘어간다. 제네릭 플랜에서는 `col = $1`이
-- `col = '리터럴'`을 함의한다고 증명할 수 없어, 그 조건을 술어로 가진 부분 인덱스를
-- 통째로 쓰지 못한다.
--
-- Hibernate는 JPQL에 enum 리터럴을 박아도 파라미터로 바꾼다. 실측(2026-09-14):
-- 로그에서 board_type='USER_CUSTOM' 0회, board_type=? 6,900회. 즉 쿼리 쪽에서 피할 방법이 없다.
--
-- 증상이 간헐적이라 더 나쁘다. 실행 횟수에 따라 계획이 바뀌므로 그냥 EXPLAIN 하면
-- 커스텀 플랜이 잡혀 재현되지 않는다. 재현하려면 SET plan_cache_mode = force_generic_plan.
--
-- 규칙: WHERE 절에 상수로 들어가는 조건(deleted_at IS NULL)만 술어에 남기고,
--       파라미터로 넘어오는 조건(rating, board_type)은 인덱스 컬럼에 둔다.


-- ─────────────────────────────────────────────────────────────
-- 1. board_feedback — 보관함 "좋아요" 탭
-- ─────────────────────────────────────────────────────────────
--
-- 걸려 있던 인덱스는 uk_board_feedback_board_user (board_id, user_id) 하나뿐이었다.
-- 선두 컬럼이 board_id라, "내가 좋아요한 보드"처럼 user_id만 조건으로 주는 조회는
-- 이 인덱스로 탐색할 수 없어 테이블을 통째로 훑고 정렬까지 하게 된다.
-- (PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들어주지 않는다. user_id는 FK지만 맨몸이었다.)
--
-- user_id와 rating이 등치 조건이므로 그 안에서 행이 이미 created_at DESC 순으로 나온다.
-- 정렬 연산이 사라지고 LIMIT이 앞부분만 읽고 멈춘다.
--
-- id를 마지막에 둔 이유: 동률 정렬 기준이 f.id DESC라, 여기까지 인덱스에 있어야
-- 정렬이 완전히 인덱스로 해결된다.

CREATE INDEX idx_feedback_user_recent
    ON board_feedback (user_id, rating, created_at DESC, id DESC);


-- ─────────────────────────────────────────────────────────────
-- 2. board — content_signature 조회
-- ─────────────────────────────────────────────────────────────
--
-- findActiveByTypeAndSignature는 추천 보드를 내려줄 때 resolveGeneratedBoard가 보드마다
-- 부르므로 홈 진입 1회에 4번, 좋아요로 보드를 저장할 때 1번 호출된다. 서비스에서 가장
-- 자주 도는 조회 중 하나다.
--
-- 그런데 V5의 uk_board_ai_signature는 board_type을 술어에 두고 있어(위 공통 근거)
-- 제네릭 플랜에서 쓰이지 못하고 board 전체를 훑는다. 실측(2026-09-14, board 3,128행):
--
--   리터럴          Index Scan using uk_board_ai_signature   buffers 2    0.012ms
--   바인드 파라미터  Seq Scan (3,128행 필터)                  buffers 74   0.607ms
--   이 인덱스 추가 후 Index Scan using idx_board_signature_lookup  buffers 2  0.031ms
--
-- 보드 수에 비례해 나빠진다. 같은 조건(좋아요 100건/워커 20)에서 board가 620행 -> 3,128행이
-- 되자 처리량이 107 TPS -> 40 TPS로 떨어졌다. pg_stat_user_tables에도 board의
-- seq_scan 13,651회 / 순차로 읽은 행 700만으로 남아 있었다.
--
-- 유니크 제약(uk_board_ai_signature, uk_board_user_signature)은 건드리지 않는다.
-- 제약의 의미가 부분적이라(삭제되지 않은 AI_RECOMMEND 안에서만 유일) 일반 유니크로
-- 바꿀 수 없다. 조회용 인덱스를 따로 두는 형태여야 한다.
--
-- deleted_at IS NULL은 술어로 남겨도 된다. 쿼리에 상수로 박혀 있어 플래너가 증명할 수 있다.
--
-- board_type을 선두에 두는 이유: USER_CUSTOM 경로(findActiveUserBoardBySignature)도
-- 같은 두 컬럼을 조건으로 쓰므로 하나의 인덱스가 두 조회를 모두 덮는다.

CREATE INDEX idx_board_signature_lookup
    ON board (board_type, content_signature)
    WHERE deleted_at IS NULL;
