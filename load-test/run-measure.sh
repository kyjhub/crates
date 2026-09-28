#!/usr/bin/env bash
# 시나리오를 워밍업 1회 + 측정 N회 돌리고 중앙값을 보고한다.
#
#   ./load-test/run-measure.sh <시나리오> [측정횟수] [--vacuum]
#
#   시나리오   load-test/scenarios/ 안의 파일명 (확장자 생략 가능)
#   측정횟수   기본 3. 1회 측정으로는 아무것도 말할 수 없다
#   --vacuum   매 실행 전 VACUUM ANALYZE. 쓰기 시나리오 뒤에 쌓인 죽은 튜플을 치운다
#
# 출발 상태 — 데이터는 prepare.sh로 한 번 만들어 두고, 매 실행 전에는 통계만 초기화한다.
# 좋아요 1,000만 건을 매번 다시 넣으면 실행마다 몇 분이 걸린다. 읽기 시나리오는 상태를 바꾸지 않고,
# 쓰기 시나리오는 좋아요→취소를 짝으로 보내 상태를 유지한다. 이 스크립트는 데이터를 지우지 않는다.
#
# 결과는 load-test/results/<시나리오>-<시각>/ 에 남는다. 회차마다 k6 요약(runN.json)과
# 부하 중 자원 사용량(runN.csv, lib/sampler.sh)이 들어간다.
#
# 왜 이런 모양인가
#   워밍업  첫 실행은 정상 상태보다 50% 느리다(JVM JIT). 한 번 버린다
#   반복    정상 상태에서도 실행 간 ±6% 변동이 있다. 중앙값을 써야 한 번의 튐에 속지 않는다
#   준비    /actuator/health 응답은 측정 준비 완료가 아니다. Qdrant 재시딩이 뒤따른다
#   샘플러  서버 퍼센타일은 창 기반이라 부하가 끝난 뒤 읽으면 비어 있다. 도는 동안 기록한다
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./crates_server.env; set +a

SCENARIO=""; RUNS=3; VACUUM=0
for arg in "$@"; do
    case "$arg" in
        --vacuum) VACUUM=1 ;;
        *) if [ -z "$SCENARIO" ]; then SCENARIO="$arg"; else RUNS="$arg"; fi ;;
    esac
done
[ -n "$SCENARIO" ] || { echo "시나리오 이름이 필요합니다 (load-test/scenarios/ 안의 파일명)" >&2; exit 1; }
NAME="${SCENARIO%.js}"
SCENARIO="load-test/scenarios/${NAME}.js"
[ -f "$SCENARIO" ] || { echo "시나리오를 찾을 수 없습니다: $SCENARIO" >&2; exit 1; }
OUT_DIR="load-test/results/${NAME}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"

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

# 매 실행의 출발 상태를 같게 만든다.
reset_data() {
    if [ "$VACUUM" = "1" ]; then
        # 좋아요→취소 짝도 죽은 튜플은 남긴다. 쌓이면 가시성 맵이 깨져 Index Only Scan이 힙을 다시 읽는다.
        $PG "VACUUM ANALYZE" >/dev/null
    fi
    $PG "SELECT pg_stat_statements_reset()" >/dev/null 2>&1 || true
}

# 한 번 실행하고 "처리율 p95 보드수" 를 돌려준다.
#   $1  결과 파일 이름(확장자 없이). 주면 k6 요약과 부하 중 자원 사용량을 남긴다. 워밍업은 남기지 않는다.
run_once() {
    local tag="${1:-}" summary sampler_pid=""
    if [ -n "$tag" ]; then
        summary="$OUT_DIR/$tag.json"
        ./load-test/lib/sampler.sh "$OUT_DIR/$tag.csv" &
        sampler_pid=$!
    else
        summary=$(mktemp -t k6sum)
    fi
    k6 run --quiet --summary-export="$summary" "$SCENARIO" >/dev/null 2>&1 || true
    if [ -n "$sampler_pid" ]; then kill "$sampler_pid" 2>/dev/null || true; wait "$sampler_pid" 2>/dev/null || true; fi
    python3 - "$summary" "$($PG 'SELECT count(*) FROM board')" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))['metrics']
d = next((v for k, v in m.items() if k.startswith('http_req_duration') and '{' not in k), None)
# 처리율은 iterations로 본다. http_reqs에는 setup의 로그인이 섞인다.
print(f"{m['iterations']['rate']:.1f} {d['p(95)']:.0f} {sys.argv[2]}")
PY
    if [ -z "$tag" ]; then rm -f "$summary"; fi
}

# 샘플러 기록에서 회차의 최대값을 뽑는다: "backend postgres qdrant k6 (CPU%) 풀대기"
peaks() {
    python3 - "$1" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
def mx(col):
    vals = [float(r[col]) for r in rows if r.get(col) not in (None, '')]
    return f"{max(vals):.0f}" if vals else "-"
print(mx('backend_cpu'), mx('postgres_cpu'), mx('qdrant_cpu'), mx('k6_cpu'), mx('hikari_pending'))
PY
}

wait_ready
start="prepare.sh로 만든 데이터 그대로$([ "$VACUUM" = 1 ] && echo " + 매회 VACUUM ANALYZE")"
echo "시나리오: $SCENARIO   측정 ${RUNS}회   출발 상태: $start"
echo "결과: $OUT_DIR"
echo

reset_data
printf '워밍업(버림) ... '
run_once >/dev/null
echo '완료'

printf '\n%6s %12s %8s %9s  %s\n' 회차 "처리율(/s)" p95ms board행 "최대 CPU% backend/pg/qdrant/k6 · 풀 대기"
printf -- '--------------------------------------------------------------------------\n'
rates=(); p95s=()
for i in $(seq 1 "$RUNS"); do
    reset_data
    read -r rate p95 boards <<<"$(run_once "run$i")"
    read -r c_be c_pg c_qd c_k6 pend <<<"$(peaks "$OUT_DIR/run$i.csv")"
    printf '%6s %12s %8s %9s  %s/%s/%s/%s · %s\n' "$i" "$rate" "$p95" "$boards" "$c_be" "$c_pg" "$c_qd" "$c_k6" "$pend"
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
