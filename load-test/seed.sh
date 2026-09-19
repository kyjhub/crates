#!/usr/bin/env bash
# 부하테스트용 계정·보드·좋아요를 준비한다.
#
#   ./load-test/seed.sh [계정수] [보드수] [계정당좋아요]
#   ./load-test/seed.sh 20 1000 0      # 기본 시나리오용 (좋아요 없는 계정 20개)
#   ./load-test/seed.sh 1  1000 200    # 이력 200건짜리 계정 하나 (프로파일링용)
#
# k6와 역할이 다르다. 여기는 "측정하려는 상태"를 만들고, k6는 그 상태에 부하를 건다.
# 그동안 k6가 둘을 겸하면서 실행마다 상태가 누적돼 비교가 깨졌다(docs 4-9).
#
# 대부분을 SQL로 꽂는다. API로 좋아요 200건을 만들면 요청 200번 + 비동기 재계산 200번이라
# 수십 초가 걸리고, 준비 자체가 시스템을 데워 측정 조건을 바꾼다.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./crates_server.env; set +a

USERS="${1:-20}"; BOARDS="${2:-1000}"; LIKES="${3:-0}"
[ "$LIKES" -le "$BOARDS" ] || { echo "계정당 좋아요($LIKES)가 보드 수($BOARDS)보다 많을 수 없습니다" >&2; exit 1; }

PSQL="docker exec -i -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates"
PSQL1="docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates -t -A -c"

# 콘텐츠가 보드 수를 감당하는지 (보드 하나에 8건씩 연속으로 쓴다)
need=$((BOARDS * 8))
have=$($PSQL1 "SELECT count(*) FROM content")
[ "$have" -ge "$need" ] || { echo "콘텐츠가 부족합니다: 보드 ${BOARDS}개에 ${need}건 필요, 현재 ${have}건" >&2; exit 1; }

# 기준 계정을 API로 한 번 만든다. BCrypt 해시와 초기 취향 벡터를 SQL로는 만들 수 없어,
# 이 계정에서 복사한다. 이미 있으면 건너뛴다.
if [ "$($PSQL1 "SELECT count(*) FROM users WHERE login_id='loadtest_seed'")" = "0" ]; then
    printf '기준 계정 생성(API) ... '
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/auth/signup \
        -H 'Content-Type: application/json' \
        -d '{"loginId":"loadtest_seed","pwd":"Loadtest!234","email":"loadtest_seed@example.com",
             "nickname":"loadtest_seed","gender":"OTHER","birthYear":"1995-01-01"}')
    case "$code" in 200|201) echo '완료';; *) echo "실패(HTTP $code) — 앱이 떠 있는지 확인하세요" >&2; exit 1;; esac
    # 취향 벡터는 가입 트랜잭션이 커밋된 뒤에 보인다
    for _ in $(seq 1 20); do
        [ "$($PSQL1 "SELECT count(*) FROM user_vector uv JOIN users u ON u.id=uv.user_id WHERE u.login_id='loadtest_seed'")" = "1" ] && break
        sleep 1
    done
fi

echo "준비: 계정 ${USERS}개 / 보드 ${BOARDS}개 / 계정당 좋아요 ${LIKES}건"
$PSQL -q -v users="$USERS" -v boards="$BOARDS" -v likes="$LIKES" < load-test/seed.sql
echo
echo "비밀번호는 모두 Loadtest!234 입니다. 정리는 load-test/cleanup.sql."
