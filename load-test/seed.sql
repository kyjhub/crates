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
--   :stride  보드 안에서 콘텐츠를 얼마나 벌려 담을지 (seed.sh가 계산해 넘긴다)
--
-- 전제: loadtest_seed 계정과 그 취향 벡터가 미리 있어야 한다. 비밀번호 해시와 취향 벡터를
--       그 계정에서 복사하기 때문이다. seed.sh가 계정은 SQL(pgcrypto)로, 취향 벡터는 가입 직후
--       콘텐츠 선택 API로 만든다(콘텐츠 벡터가 Qdrant에 있어 SQL로는 평균을 낼 수 없다).

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

-- 취향 벡터가 없으면 추천 조회가 BusinessException으로 죽는다. 가입 직후 콘텐츠를 고를 때
-- 만들어지는 행을 여기서 대신 만든다. 벡터와 고른 콘텐츠 id를 기준 계정 것으로 채운다 —
-- 좋아요 재계산이 이 id를 다시 읽으므로 벡터만 복사하면 첫 좋아요부터 계산이 어긋난다.
INSERT INTO user_vector (user_id, user_vector, initial_content_ids, updated_at)
SELECT u.id, s.user_vector, s.initial_content_ids, now()
FROM users u
CROSS JOIN (SELECT uv.user_vector, uv.initial_content_ids FROM user_vector uv
            JOIN users b ON b.id = uv.user_id WHERE b.login_id = 'loadtest_seed') s
WHERE u.login_id LIKE 'loadtest_u%'
ON CONFLICT (user_id) DO NOTHING;

-- ── 보드 ────────────────────────────────────────────────────
-- 보드 k는 콘텐츠 위치 (k-1), (k-1)+S, (k-1)+2S, ... (k-1)+7S 를 담는다. S는 :stride.
--
-- 예전에는 (k-1)*8+1 .. +8 연속 블록을 썼다. 보드끼리 콘텐츠가 전혀 겹치지 않아
-- 만들 수 있는 보드가 콘텐츠/8 (약 23,900개)로 막혔다. 실제 서비스에서는 서로 다른 보드가
-- 같은 영화를 담는 것이 정상이고, content_signature만 다르면 제약도 걸리지 않는다.
--
-- 슬라이딩 윈도우로 바꾸면서 얻는 것
--   · 보드 상한이 콘텐츠 - 7*S 로 늘어난다 (S=1000이면 약 184,000개)
--   · 보드 k와 k+S가 콘텐츠 7건을 공유한다 — 운영에 가까운 중복 분포
--   · 보드 k와 k+1은 한 건도 겹치지 않는다 (위치가 통째로 1씩 밀린다)
--
-- 유지되는 성질
--   · 한 보드 안에서 위치가 S씩 증가하므로 콘텐츠가 겹치지 않는다 (uk_board_item_content)
--   · 보드마다 시작 위치가 달라 signature가 전부 다르다 (uk_board_ai_signature)
--   · 위치가 오름차순이라 id도 오름차순이다 — Board.signatureOf가 요구하는 정렬과 같다
--
-- content_signature는 Board.signatureOf와 같은 규칙이다 — id를 오름차순 정렬해 콤마로 잇는다.
-- 앱이 계산한 값과 글자 하나라도 다르면 중복 검사가 어긋나 보드가 두 벌로 쌓인다.
--
-- id를 직접 계산하지 않고 위치(row_number)로 다루는 이유: 콘텐츠 id에 빈틈이 생기면
-- 없는 id를 참조해 FK에서 깨진다. 지금은 1..191239로 연속이지만 그것에 기대지 않는다.
CREATE TEMP TABLE content_pos ON COMMIT DROP AS
SELECT id, (row_number() OVER (ORDER BY id) - 1) AS pos FROM content;
CREATE INDEX ON content_pos (pos);

CREATE TEMP TABLE seeded_board ON COMMIT DROP AS
SELECT k,
       array_agg(c.id ORDER BY j) AS ids,
       string_agg(c.id::text, ',' ORDER BY j) AS signature
FROM generate_series(1, :boards) k
CROSS JOIN generate_series(1, 8) j
JOIN content_pos c ON c.pos = (k - 1) + (j - 1) * :stride
GROUP BY k;

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

-- 셸이 파싱할 수 있도록 한 줄로 내보낸다. seed.sh가 이 값으로 분포를 검증한다.
--   계정 보드 좋아요 인당좋아요 좋아요받은보드 구간1..구간5
SELECT
    (SELECT count(*) FROM users WHERE login_id LIKE 'loadtest_u%')                                  AS users,
    (SELECT count(*) FROM board WHERE title LIKE 'loadtest-seeded-%')                               AS boards,
    (SELECT count(*) FROM board_feedback)                                                           AS likes,
    COALESCE((SELECT round(avg(c)) FROM (SELECT count(*) c FROM board_feedback GROUP BY user_id) s), 0) AS per_user,
    (SELECT count(DISTINCT board_id) FROM board_feedback)                                           AS liked_boards,
    COALESCE((SELECT string_agg(cnt::text, ' ' ORDER BY bucket) FROM (
        SELECT b.bucket, count(f.id) AS cnt
        FROM (SELECT id, ntile(5) OVER (ORDER BY id) AS bucket
              FROM board WHERE title LIKE 'loadtest-seeded-%') b
        LEFT JOIN board_feedback f ON f.board_id = b.id
        GROUP BY b.bucket) x), '0 0 0 0 0')                                                         AS buckets;
