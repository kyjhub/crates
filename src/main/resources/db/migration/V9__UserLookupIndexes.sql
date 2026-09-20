-- users 조회 인덱스.
--
-- 이 테이블의 인덱스는 PK와 uk_provider_and_id 둘뿐이었다. 그래서 로그인할 때마다,
-- 그리고 가입 중복 확인을 할 때마다 users 전체를 훑었다.
--
--   Seq Scan on users (rows=1)        Buffers: shared hit=25
--     Filter: ((login_id)::text = $1)
--     Rows Removed by Filter: 1000
--
-- 사용자 1,001명에서는 0.039ms라 눈에 띄지 않는다. 문제는 비용이 O(사용자 수)라는 것이다.
-- 100만 명이면 로그인 시도 한 번에 약 25,000블록(200MB)을 읽는다. 게다가 이 경로는
-- 로그인이 실패할 때도 돈다 — 인증 없이 외부에서 부를 수 있는 엔드포인트가 풀스캔을 유발한다.
--
-- UNIQUE인 이유: AuthService가 가입 시점에 세 값의 중복을 이미 막고 있다(existsByLoginId,
-- existsByEmail, existsByNickname). 애플리케이션만 아는 규칙을 DB도 알게 한다. 조회를
-- 빠르게 하는 것과 규칙을 강제하는 것을 인덱스 하나로 같이 얻는다.
--
-- 탈퇴한 사용자(deleted_at IS NOT NULL)의 값도 계속 점유한다. 부분 인덱스로 풀어주지 않는다 —
-- 지금 existsBy* 세 메서드에 deleted_at 조건이 없어서 앱은 이미 "탈퇴해도 아이디는 반납되지
-- 않는다"로 동작하고 있다. 여기서 규칙을 바꾸면 인덱스 추가가 아니라 정책 변경이 된다.
--
-- NULL은 막지 않는다. PostgreSQL의 UNIQUE는 NULL을 서로 다른 값으로 보므로(NULLS DISTINCT),
-- login_id가 없는 소셜 가입 계정이 여럿이어도 충돌하지 않는다.
--
-- IF NOT EXISTS를 쓰는 이유: 성능 측정 과정에서 같은 인덱스를 손으로 먼저 만들어 본 개발
-- 데이터베이스가 있다. 그 환경에서도 이 마이그레이션이 그대로 통과해야 한다.
--
-- CONCURRENTLY는 쓰지 않는다. Flyway가 마이그레이션을 트랜잭션으로 감싸는데 CONCURRENTLY는
-- 트랜잭션 안에서 돌 수 없다. 대신 인덱스를 만드는 동안 users에 쓰기 잠금이 걸린다 —
-- 행 수가 적을 때 적용해야 한다.

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_login_id ON users (login_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email    ON users (email);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_nickname ON users (nickname);
