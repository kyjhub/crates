#!/usr/bin/env bash
# 시나리오를 워밍업 1회 + 측정 N회 돌리고 중앙값을 보고한다.
#
#   ./load-test/run-measure.sh <시나리오> [측정횟수] [--keep]
#
#   시나리오   load-test/scenarios/ 안의 파일명 (확장자 생략 가능)
#   측정횟수   기본 3. 1회 측정으로는 아무것도 말할 수 없다
#   --keep     매 실행 전 초기화를 건너뛴다 (board 누적 효과를 보려는 board-growth용)
#
#   SEED 환경변수로 매 실행의 출발 상태를 정한다 ("계정수 보드수 계정당좋아요").
#     SEED="20 600 0"   기본. 좋아요 없는 계정 20개와 보드 600개
#     SEED="10 500 150" 1인당 이력 150건에서 시작 (like-history용)
#
# 왜 이런 모양인가
#   워밍업  첫 실행은 정상 상태보다 50% 느리다(JVM JIT). 한 번 버린다
#   반복    정상 상태에서도 실행 간 ±6% 변동이 있다. 중앙값을 써야 한 번의 튐에 속지 않는다
#   준비    /actuator/health 응답은 측정 준비 완료가 아니다. Qdrant 재시딩이 뒤따른다
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./crates_server.env; set +a

SCENARIO="${1:?시나리오 이름이 필요합니다 (예: like-new-board)}"
SCENARIO="load-test/scenarios/${SCENARIO%.js}.js"
[ -f "$SCENARIO" ] || { echo "시나리오를 찾을 수 없습니다: $SCENARIO" >&2; exit 1; }
RUNS="${2:-3}"
SEED="${SEED:-${USERS:-20} 600 0}"
case "${3:-}${2:-}" in *--keep*) KEEP=1;; *) KEEP=0;; esac

PG="docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates -t -A -c"

# ── 측정 준비 확인 ─────────────────────────────────────────────
# health가 200/401을 주는 시점은 시딩 완료가 아니다. Spring Boot는 ApplicationRunner를
# "Started" 로그 이후에 실행하고 웹 서버는 그보다 먼저 열린다. 그리고 seed completed 로그가
# 찍힌 뒤에도 Qdrant가 HNSW 인덱스를 백그라운드로 짓는다(실측 35초, CPU 815%).
wait_ready() {
    printf '준비 확인: '
    for _ in $(seq 1 60); do
        [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health || true)" != "000" ] && break
        printf '.'; sleep 5
    done
    # grep -q를 파이프라인에 쓰면 안 된다. 먼저 종료하면서 docker logs가 SIGPIPE로 죽고,
    # set -o pipefail이 그것을 실패로 잡아 멀쩡한 상태에서도 여기서 멈춘다.
    local seeded; seeded=$(docker logs backend_server 2>&1 | grep -c "seed completed" || true)
    [ "${seeded:-0}" -gt 0 ] || { echo "Qdrant 시딩 로그 없음" >&2; exit 1; }
    for _ in $(seq 1 60); do
        cpu=$(docker stats --no-stream --format '{{.CPUPerc}}' qdrant_server | tr -d '%')
        awk -v c="$cpu" 'BEGIN{exit !(c<20)}' && { echo "완료 (Qdrant ${cpu}%)"; return; }
        printf '.'; sleep 10
    done
    echo "Qdrant가 계속 바쁩니다 (${cpu}%) — 측정을 신뢰할 수 없습니다" >&2; exit 1
}

# 매 실행의 출발 상태를 같게 만든다. 지우기만 하면 시나리오가 쓸 계정도 사라지므로
# 지운 뒤 다시 심는다. 데이터 준비는 k6가 아니라 seed.sh의 몫이다(docs 4-9).
reset_data() {
    [ "$KEEP" = "1" ] && return
    docker exec -i -e PGPASSWORD="$POSTGRESQL_PASSWORD" postgres_server \
        psql -U "$POSTGRESQL_USERNAME" -d crates -q -v ON_ERROR_STOP=1 < load-test/cleanup.sql >/dev/null
    # shellcheck disable=SC2086
    ./load-test/seed.sh $SEED >/dev/null
    $PG "VACUUM ANALYZE board" >/dev/null
    $PG "SELECT pg_stat_statements_reset()" >/dev/null 2>&1 || true
}

# 한 번 실행하고 "처리율 p95 보드수" 를 돌려준다.
run_once() {
    local summary; summary=$(mktemp -t k6sum)
    k6 run --quiet --summary-export="$summary" "$SCENARIO" >/dev/null 2>&1 || true
    python3 - "$summary" "$($PG 'SELECT count(*) FROM board')" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))['metrics']
d = next((v for k, v in m.items() if k.startswith('http_req_duration') and '{' not in k), None)
# 처리율은 iterations로 본다. http_reqs에는 setup의 회원가입·로그인이 섞인다.
print(f"{m['iterations']['rate']:.1f} {d['p(95)']:.0f} {sys.argv[2]}")
PY
    rm -f "$summary"
}

wait_ready
echo "시나리오: $SCENARIO   측정 ${RUNS}회   출발 상태: $([ "$KEEP" = 1 ] && echo "유지(--keep)" || echo "seed.sh $SEED")"
echo

reset_data
printf '워밍업(버림) ... '
run_once >/dev/null
echo '완료'

printf '\n%6s %12s %10s %10s\n' 회차 "처리율(/s)" p95ms board행
printf -- '----------------------------------------\n'
rates=(); p95s=()
for i in $(seq 1 "$RUNS"); do
    reset_data
    read -r rate p95 boards <<<"$(run_once)"
    printf '%6s %12s %10s %10s\n' "$i" "$rate" "$p95" "$boards"
    rates+=("$rate"); p95s+=("$p95")
done

python3 - "${rates[*]}" "${p95s[*]}" <<'PY'
import statistics, sys
r = [float(x) for x in sys.argv[1].split()]
p = [float(x) for x in sys.argv[2].split()]
spread = (max(r) - min(r)) / statistics.median(r) * 100 if len(r) > 1 else 0
print(f"\n중앙값: 처리율 {statistics.median(r):.1f}/s   p95 {statistics.median(p):.0f}ms")
print(f"퍼짐:   {spread:.0f}%  (정상 상태 기대치 ±6% 안팎)")
if spread > 15:
    print("\n⚠️  퍼짐이 큽니다. 다른 프로세스가 CPU를 쓰고 있는지 확인하고 다시 재십시오.")
print("\n비교할 때: 두 설정의 차이가 퍼짐보다 작으면 유의하지 않습니다.")
PY
