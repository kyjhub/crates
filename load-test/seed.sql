-- 부하테스트용 데이터를 SQL로 직접 꽂는다.
--
-- 왜 API가 아니라 SQL인가
--   "좋아요 200건짜리 계정"을 API로 만들면 요청 200번이 나가고 그때마다 취향 벡터 재계산이
--   비동기로 돌아 수십 초가 걸린다. 준비 자체가 부하가 되어, 측정하려는 상태에 도달하기 전에
--   시스템이 데워지거나 지쳐버린다. SQL로 꽂으면 수백 ms다.
--
--   더 중요한 이유는 역할 분리다. 그동안 k6가 데이터 생성과 부하 생성을 겸하면서 실행마다
--   상태가 누적됐고, 그것이 "개선을 퇴행으로 잘못 읽은" 원인이었다(docs 4-9).
--
-- psql 변수로 규모를 받는다.
--   :users   만들 계정 수
--   :boards  만들 보드 수 (AI_RECOMMEND)
--   :likes   계정당 좋아요 수 (boards 이하여야 한다)
--
-- 전제: loadtest_seed 계정이 API 회원가입으로 미리 만들어져 있어야 한다. 비밀번호 해시와
--       취향 벡터를 그 계정에서 복사하기 때문이다(BCrypt 해시를 SQL로 만들 수 없다).
--       seed.sh가 그 부분을 처리한다.

\set ON_ERROR_STOP on
BEGIN;

-- ── 계정 ────────────────────────────────────────────────────
-- 비밀번호 해시는 기준 계정에서 복사한다. BCrypt는 솔트가 달라도 같은 평문을 검증하므로
-- 모든 테스트 계정이 같은 비밀번호로 로그인된다.
INSERT INTO users (login_id, pwd, email, nickname, birth_date, role, gender, login_type, created_at, updated_at)
SELECT 'loadtest_u' || g,
       (SELECT pwd FROM users WHERE login_id = 'loadtest_seed'),
       'loadtest_u' || g || '@example.com', 'loadtest_u' || g,
       DATE '1995-01-01', 'USER', 'OTHER', 'LOCAL', now(), now()
FROM generate_series(0, :users - 1) g
ON CONFLICT DO NOTHING;

-- 취향 벡터가 없으면 추천 조회가 BusinessException으로 죽는다. 가입 시 만들어지는 행을
-- 여기서 대신 만든다. 값은 기준 계정 것을 복사한다 — 첫 좋아요에 어차피 덮어쓰인다.
INSERT INTO user_vector (user_id, user_vector, updated_at)
SELECT u.id, (SELECT user_vector FROM user_vector uv
              JOIN users s ON s.id = uv.user_id WHERE s.login_id = 'loadtest_seed'), now()
FROM users u WHERE u.login_id LIKE 'loadtest_u%'
ON CONFLICT (user_id) DO NOTHING;

-- ── 보드 ────────────────────────────────────────────────────
-- 보드 k는 콘텐츠 (k-1)*8+1 .. (k-1)*8+8 을 담는다. 연속 구간이라
--   · 한 보드 안에서 콘텐츠가 겹치지 않고 (uk_board_item_content)
--   · 보드마다 signature가 달라진다 (uk_board_ai_signature)
-- content_signature는 Board.signatureOf와 같은 규칙이다 — id를 오름차순 정렬해 콤마로 잇는다.
-- 앱이 계산한 값과 글자 하나라도 다르면 중복 검사가 어긋나 보드가 두 벌로 쌓인다.
CREATE TEMP TABLE seeded_board ON COMMIT DROP AS
WITH picked AS (
    SELECT k, array_agg(((k - 1) * 8 + j)::bigint ORDER BY j) AS ids
    FROM generate_series(1, :boards) k, generate_series(1, 8) j
    GROUP BY k
)
SELECT k, ids, array_to_string(ids, ',') AS signature FROM picked;

INSERT INTO board (user_id, board_type, visibility, title, content_signature, like_count, created_at)
SELECT NULL, 'AI_RECOMMEND', 'PUBLIC', 'loadtest-seeded-' || k, signature, 0, now()
FROM seeded_board
ON CONFLICT DO NOTHING;

INSERT INTO board_item (board_id, content_id, slot_no)
SELECT b.id, s.ids[j], j
FROM seeded_board s
JOIN board b ON b.content_signature = s.signature AND b.board_type = 'AI_RECOMMEND'
CROSS JOIN generate_series(1, 8) j;

-- ── 좋아요 ──────────────────────────────────────────────────
-- 좋아요를 보드 전 구간에 고르게 흩뿌린다.
--
-- 앞에서부터 :likes 개를 집으면(ORDER BY id LIMIT n) 모든 사용자가 같은 앞쪽 보드만
-- 좋아요하고 나머지는 0건이 된다. 그러면 조인 비용이 실제보다 작게 나온다 — 실측에서
-- 좋아요가 앞쪽에 몰렸을 때 58버퍼, 전 구간에 흩어졌을 때 480버퍼로 8배 차이가 났다.
-- 측정하려는 것을 측정기가 왜곡하는 경우라 반드시 흩어야 한다.
--
-- 흩는 방법: 보드를 순번(rn)으로 세우고, 사용자마다 시작점을 어긋나게 준 뒤
-- stride(= 보드수/좋아요수) 간격으로 집는다. 결정적이라 같은 인자면 같은 데이터가 나온다.
--   stride 간격이라 한 사용자의 :likes 개가 전 구간에 퍼지고,
--   시작점이 사용자마다 달라 사용자끼리도 겹치는 보드가 적다.
--
-- 시각을 하루씩 벌려 둔다. 가중치가 "좋아요한 날짜의 최신순 순위"라 전부 같은 날이면
-- 가중치가 모두 1이 되어 실제와 다른 분포가 된다.
WITH pool AS (
    SELECT id, (row_number() OVER (ORDER BY id) - 1) AS rn, count(*) OVER () AS total
    FROM board WHERE title LIKE 'loadtest-seeded-%' AND deleted_at IS NULL
),
seeded_user AS (
    SELECT id, (row_number() OVER (ORDER BY id) - 1) AS ui
    FROM users WHERE login_id LIKE 'loadtest_u%'
),
pick AS (
    SELECT u.id AS user_id, j,
           ((u.ui * 7919) + j * GREATEST((SELECT total FROM pool LIMIT 1) / :likes, 1))
               % (SELECT total FROM pool LIMIT 1) AS rn
    FROM seeded_user u
    CROSS JOIN generate_series(0, :likes - 1) j
)
INSERT INTO board_feedback (board_id, user_id, rating, created_at)
SELECT p.id, pick.user_id, 'LIKE', now() - (pick.j || ' days')::interval
FROM pick JOIN pool p ON p.rn = pick.rn
ON CONFLICT DO NOTHING;

-- like_count를 실제 좋아요 수와 맞춘다. 안 맞으면 인기 보드 정렬이 엉뚱해진다.
UPDATE board SET like_count = c.n
FROM (SELECT board_id, count(*) n FROM board_feedback GROUP BY board_id) c
WHERE board.id = c.board_id;

COMMIT;

ANALYZE users; ANALYZE user_vector; ANALYZE board; ANALYZE board_item; ANALYZE board_feedback;

SELECT (SELECT count(*) FROM users WHERE login_id LIKE 'loadtest_u%') AS 계정,
       (SELECT count(*) FROM board WHERE title LIKE 'loadtest-seeded-%') AS 보드,
       (SELECT count(*) FROM board_feedback) AS 좋아요,
       (SELECT round(avg(c)) FROM (SELECT count(*) c FROM board_feedback GROUP BY user_id) s) AS 인당좋아요;
