#!/usr/bin/env bash
# 부하가 도는 동안 자원 사용량과 커넥션 풀 상태를 CSV로 남긴다. run-measure.sh가 백그라운드로 켜고 끈다.
#
#   ./load-test/lib/sampler.sh <출력.csv> [간격초=2]
#
# 간격은 목표값이다. docker stats가 한 번에 2초 가까이 걸려 실제로는 2~3초마다 한 줄이 된다.
#
# 한 줄에 CPU와 풀을 같이 적는 이유 — 처리율이 더 오르지 않을 때 병목을 한 줄에서 판정하기 위해서다.
#   backend CPU가 상한에 붙음            → 앱 CPU
#   CPU는 여유인데 hikari pending이 오름  → 커넥션 풀
#   postgres·qdrant CPU가 상한에 붙음     → 그 서비스
#   k6 CPU가 높음                        → 서버가 아니라 부하 생성기의 한계. 결과를 믿지 말 것
#
# CPU%는 docker stats 기준이라 코어 1개 = 100%다. backend 상한 4코어면 400%가 천장이다.
set -uo pipefail
cd "$(dirname "$0")/../.."

OUT="${1:?출력 파일 경로가 필요합니다}"
INTERVAL="${2:-2}"
BASE="${BASE_URL:-http://localhost:8080}"
CONTAINERS=(backend_server postgres_server qdrant_server)

# actuator도 JWT가 필요하다(SecurityConfig). 시딩된 계정으로 받는다.
login() {
    curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        -d '{"loginId":"loadtest_u0","pwd":"Loadtest!234"}' |
        python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null
}

# 지표 하나의 현재 값. 실패하면 빈 값을 돌려준다(토큰 만료면 호출한 쪽이 다시 로그인한다).
metric() {
    curl -s -H "Authorization: Bearer $TOKEN" "$BASE/actuator/metrics/$1" |
        python3 -c "import json,sys;print(json.load(sys.stdin)['measurements'][0]['value'])" 2>/dev/null
}

TOKEN=$(login)
[ -n "$TOKEN" ] || { echo "sampler: 로그인 실패 — 계정 loadtest_u0이 없습니다. prepare.sh를 먼저 돌리세요" >&2; exit 1; }

echo "time,backend_cpu,postgres_cpu,qdrant_cpu,backend_mem_mib,postgres_mem_mib,qdrant_mem_mib,k6_cpu,hikari_active,hikari_pending" > "$OUT"

while :; do
    started=$(date +%s)
    now=$(date +%H:%M:%S)
    # 이름 순서를 고정하려고 컨테이너를 명시한다. MemUsage는 "512MiB / 3GiB" 꼴이라 앞부분을 MiB로 바꾼다.
    stats=$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' "${CONTAINERS[@]}" 2>/dev/null |
        awk '{
            cpu[$1] = $2; sub(/%/, "", cpu[$1])
            m = $3; v = m + 0
            if (m ~ /GiB/) v *= 1024; else if (m ~ /KiB/) v /= 1024
            mem[$1] = sprintf("%.0f", v)
        } END {
            printf "%s,%s,%s,%s,%s,%s", cpu["backend_server"], cpu["postgres_server"], cpu["qdrant_server"],
                   mem["backend_server"], mem["postgres_server"], mem["qdrant_server"]
        }')
    # k6는 컨테이너가 아니라 맥에서 돈다. 여러 프로세스면 합친다.
    k6=$(ps -A -o %cpu=,comm= | awk '$2 ~ /(^|\/)k6$/ { s += $1 } END { printf "%.1f", s }')
    active=$(metric hikaricp.connections.active)
    if [ -z "$active" ]; then TOKEN=$(login); active=$(metric hikaricp.connections.active); fi
    pending=$(metric hikaricp.connections.pending)
    echo "$now,$stats,$k6,${active:-},${pending:-}" >> "$OUT"
    # docker stats 한 번에 2초 가까이 걸린다. 그만큼 덜 쉬어야 간격이 지켜진다(못 지키면 쉬지 않고 바로 다음 줄).
    rest=$(( INTERVAL - ($(date +%s) - started) ))
    [ "$rest" -gt 0 ] && sleep "$rest"
done
