#!/usr/bin/env bash
# 시딩한 계정 여럿으로 로그인해 토큰을 JSON 배열 파일로 남긴다. run-sweep.sh가 측정 전에 한 번 부른다.
#
#   ./load-test/lib/tokens.sh <출력.json> [간격=6]
#
# 왜 k6 setup에서 하지 않나 — 로그인은 BCrypt라 200번이면 backend CPU가 4코어 상한(400%)까지 치솟는다.
# setup에서 하면 그 순간이 자원 기록과 다음 단계의 출발 상태에 섞인다(2026-10-04 실측: 인기 보드 5/s에서
# backend CPU 최대 397%로 기록됨). 측정 밖에서 한 번 받아 두고 단계마다 같은 토큰을 쓴다.
#
# 왜 여럿인가 — 한 계정으로만 두드리면 같은 행·같은 캐시만 읽어 실제보다 빠르게 나온다.
# 계정마다 좋아요 수(가입 시기)가 달라 보관함·추천·재계산 비용도 다르다.
# 간격 6이면 1,200명 중 200명이고, seed.sql이 가입 순서대로 번호를 매기므로 가입 시기 분포가 그대로 남는다.
#
# 토큰 수명은 1시간이다. 스윕 하나는 그보다 짧다.
set -euo pipefail
OUT="${1:?출력 파일 경로가 필요합니다}"
STRIDE="${2:-6}"
BASE="${BASE_URL:-http://localhost:8080}"
export BASE

login_one() {
    curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        -d "{\"loginId\":\"loadtest_u$1\",\"pwd\":\"Loadtest!234\"}" |
        python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null ||
        echo "FAIL u$1"
}
export -f login_one

seq 0 "$STRIDE" 1199 | xargs -P 8 -I{} bash -c 'login_one {}' > "$OUT.tmp"
if grep -q '^FAIL' "$OUT.tmp"; then
    echo "로그인 실패: $(grep -c '^FAIL' "$OUT.tmp")건 — prepare.sh로 데이터를 먼저 준비하세요" >&2
    rm -f "$OUT.tmp"; exit 1
fi
python3 -c "import json,sys;print(json.dumps([l.strip() for l in open(sys.argv[1]) if l.strip()]))" "$OUT.tmp" > "$OUT"
rm -f "$OUT.tmp"
