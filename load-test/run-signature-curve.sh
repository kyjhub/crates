#!/usr/bin/env bash
# board 테이블을 키워가며 처리량이 유지되는지 본다 (V7 인덱스 검증).
#
# 회차마다 같은 부하를 넣되 board 행만 늘어난다. 처리량이 평평하면 인덱스가 듣는 것이고,
# 회차에 따라 떨어지면 signature 조회가 다시 전체 스캔으로 떨어진 것이다.
#
# 사용법:  ./load-test/run-signature-curve.sh [회차수]
# 전제:    앱이 8080에 떠 있고, crates_server.env 가 저장소 루트에 있다.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./crates_server.env; set +a

ROUNDS=${1:-5}
SUMMARY=$(mktemp -t k6sum)
trap 'rm -f "$SUMMARY"' EXIT

PG="docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates -t -A -c"

count_board() { $PG "SELECT count(*) FROM board WHERE deleted_at IS NULL"; }
seq_scans()   { $PG "SELECT seq_scan FROM pg_stat_user_tables WHERE relname='board'"; }

printf '%6s %10s %10s %10s %12s %12s\n' 회차 시작보드 끝보드 seq스캔증가 TPS p95ms
printf '%s\n' "----------------------------------------------------------------------"

for r in $(seq 1 "$ROUNDS"); do
    before_board=$(count_board); before_seq=$(seq_scans)

    # --summary-export 는 파일 경로를 받는다(/dev/stdout 불가). 회차마다 덮어쓴다.
    k6 run --quiet --summary-export="$SUMMARY" \
        load-test/scenarios/board-signature.js >/dev/null 2>&1 || true

    read -r tps p95 <<<"$(python3 -c "
import json
m = json.load(open('$SUMMARY'))['metrics']
key = next((k for k in m if k.startswith('http_req_duration') and 'boards/likes' in k), 'http_req_duration')
print(round(m['http_reqs']['rate'], 1), round(m[key]['p(95)']))
")"

    after_board=$(count_board); after_seq=$(seq_scans)
    printf '%6s %10s %10s %10s %12s %12s\n' \
        "$r" "$before_board" "$after_board" "$((after_seq - before_seq))" "$tps" "$p95"
done

echo
echo "기준선(인덱스 없던 2026-09-14): board 620행 107 TPS -> 3,128행 40 TPS"
echo "seq스캔증가가 회차마다 크게 늘면 signature 조회가 인덱스를 못 타는 것이다."
