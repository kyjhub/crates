#!/usr/bin/env bash
# 부하테스트용 계정·보드·좋아요를 준비한다.
#
#   ./load-test/seed.sh [계정수] [보드수] [계정당좋아요]
#   ./load-test/seed.sh 20 1000 0        # 기본 시나리오용 (좋아요 없는 계정 20개)
#   ./load-test/seed.sh 1  1000 200      # 이력 200건짜리 계정 하나 (프로파일링용)
#   ./load-test/seed.sh 200 20000 180    # 플래너가 인덱스를 고르기 시작하는 규모
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

# 보드 하나는 콘텐츠를 stride 간격으로 8건 담는다(슬라이딩 윈도우).
# 마지막 보드가 쓰는 위치가 (BOARDS-1) + 7*STRIDE 이므로 그것이 콘텐츠 수 안에 들어와야 한다.
have=$($PSQL1 "SELECT count(*) FROM content")
[ "$BOARDS" -le $((have - 7)) ] || {
    echo "보드가 너무 많습니다: 최대 $((have - 7))개 (콘텐츠 ${have}건)" >&2; exit 1; }

# stride는 남는 공간을 7등분해 최대한 벌린다. 넓을수록 한 보드의 콘텐츠가 고루 퍼지고,
# 보드 k와 k+stride가 콘텐츠 7건을 공유해 운영에 가까운 중복 분포가 된다.
# 1000을 넘기지 않는 이유는 그 이상 벌려도 이득이 없고 상한만 줄기 때문이다.
STRIDE=$(( (have - BOARDS) / 7 ))
[ "$STRIDE" -gt 1000 ] && STRIDE=1000
[ "$STRIDE" -lt 1 ] && STRIDE=1

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

echo "준비: 계정 ${USERS}개 / 보드 ${BOARDS}개 / 계정당 좋아요 ${LIKES}건 (콘텐츠 간격 ${STRIDE})"
read -r n_users n_boards n_likes per_user liked_boards b1 b2 b3 b4 b5 <<<"$(
    $PSQL -q -t -A -F' ' -v users="$USERS" -v boards="$BOARDS" -v likes="$LIKES" -v stride="$STRIDE" < load-test/seed.sql
)"

printf '  계정 %s / 보드 %s / 좋아요 %s (인당 %s)\n' "$n_users" "$n_boards" "$n_likes" "$per_user"

# ── 분포 검증 ──────────────────────────────────────────────
# 좋아요가 앞쪽 보드에만 몰리면 조인 비용이 실제보다 작게 나온다. 실측에서 몰렸을 때
# 58버퍼, 전 구간에 흩어졌을 때 480버퍼로 8배 차이가 났다. 측정기가 결과를 왜곡하는
# 종류라, 시더를 고칠 때마다 사람이 기억하지 않아도 드러나도록 여기서 확인한다.
# (실제로 이 왜곡을 한 번 발견해 문서에 적어두고도 시더에서 그대로 반복했다.)
if [ "$n_likes" -gt 0 ]; then
    printf '  좋아요 분포(보드 5구간): %s %s %s %s %s   받은 보드 %s/%s\n' \
        "$b1" "$b2" "$b3" "$b4" "$b5" "$liked_boards" "$n_boards"

    # 모든 사용자가 같은 보드를 집으면 받은 보드 수가 계정당 좋아요 수를 넘지 못한다.
    if [ "$n_users" -gt 1 ] && [ "$liked_boards" -le "$LIKES" ]; then
        echo "  ⚠️  사용자들이 같은 보드에만 좋아요했습니다 — 분포가 퍼지지 않았습니다." >&2
        echo "      seed.sql의 pick CTE(stride 계산)를 확인하세요." >&2
        exit 1
    fi
    # 한 구간이 균등분의 3배를 넘으면 한쪽으로 쏠린 것이다.
    even=$(( n_likes / 5 ))
    for b in "$b1" "$b2" "$b3" "$b4" "$b5"; do
        if [ "$even" -gt 0 ] && [ "$b" -gt $(( even * 3 )) ]; then
            echo "  ⚠️  좋아요가 특정 구간에 쏠렸습니다(구간 ${b}건 vs 균등 ${even}건)." >&2
            exit 1
        fi
    done
fi

echo
echo "비밀번호는 모두 Loadtest!234 입니다. 정리는 load-test/cleanup.sql."
