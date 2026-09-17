# 부하테스트

k6로 부하를 만들고, 앱과 DB가 스스로 내보내는 값으로 내부를 본다.

측정 결과와 발견한 문제는 [docs/load-test-and-metrics.md](../docs/load-test-and-metrics.md)에 있다.

## 세 층을 한 도구로 보려 하지 말 것

| 층 | 재는 것 | 도구 |
|---|---|---|
| ① | 사용자가 겪는 시간 | **k6** |
| ② | 앱 내부 (커넥션 풀 대기, 재계산 소요) | **`/actuator/prometheus`** |
| ③ | 어느 쿼리가 시간을 먹는가 | **`pg_stat_statements`** |

k6가 재는 건 ①뿐이다. DB 시간은 그 숫자 **안에 섞여** 있을 뿐 분리되지 않는다.
"느리다"는 알아도 "왜"는 ②③이 답한다.

## 전제

`k6` 설치(`brew install k6`)와 `crates_server.env`가 저장소 루트에 있을 것.

**앱은 컨테이너로 띄운다.** 자원 상한(`cpus: '4'`, `memory: 3g`)이 걸려야 수치가 재현된다.

```bash
docker compose --env-file crates_server.env down -v
docker compose --env-file crates_server.env up -d --build
```

기동에 4~5분 걸린다(콘텐츠 19만 건 시딩 + 이미지 업로드 + Qdrant 적재).
`docker compose ps`로 `backend_server`가 뜬 것을 확인하고 시작한다.

> `--build`를 붙이는 이유: `postgres/init.sql`(pg_stat_statements 생성)이 이미지에 구워져 있어,
> 캐시된 이미지를 쓰면 확장이 안 만들어진다.

> `./gradlew bootRun`으로 띄우고 재지 말 것. `spring-boot-devtools`가 활성화돼
> 재시작 클래스로더가 끼고, 자원 상한도 없어서 **수치가 재현되지 않는다.**

## 실행

```bash
# 보드를 늘려가며 처리량이 유지되는지 (V7 인덱스 검증)
./load-test/run-signature-curve.sh 5

# 시나리오 단독 실행
USERS=20 ITERATIONS=600 k6 run load-test/scenarios/board-signature.js
```

환경변수: `USERS`(기본 20), `ITERATIONS`(기본 100), `BASE_URL`, `MAX_CONTENT_ID`(기본 191239).
`MAX_CONTENT_ID`는 시딩 결과가 바뀌면 함께 바꿔야 한다.

## 읽는 법

**측정 전에 리셋한다.** 안 하면 초기 시딩 쿼리가 순위를 독식한다(실제로 전체의 69%를 차지한 적이 있다).

```sql
SELECT pg_stat_statements_reset();
```

**② 앱 내부는 부하가 도는 중에 읽는다.**

```bash
TOKEN=...   # loadtest_* 계정으로 로그인해 얻는다
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/prometheus \
  | grep -E 'crates_user_vector|hikaricp_connections_(pending|acquire|timeout)'
```

- `crates_user_vector_stale_users` — **0이 정상.** 0이 아닌 상태가 이어지면 재계산이 밀렸거나 유실된 것
- `crates_user_vector_recalculation_failures_total` — 재계산 예외
- `hikaricp_connections_pending` / `_timeout_total` — 커넥션 풀 포화

> **퍼센타일은 창(window) 기반이다.** 부하가 끝난 뒤 읽으면 전부 0으로 보인다.
> `count`/`sum`/`max`는 누적이라 남는다.

**③ 어느 쿼리가 범인인가.**

```sql
SELECT round(total_exec_time)::text||'ms' AS 총시간,
       round(100*total_exec_time/sum(total_exec_time) OVER ())::text||'%' AS 비중,
       calls, round(mean_exec_time::numeric,2) AS 평균ms,
       left(regexp_replace(query,'\s+',' ','g'), 70) AS 쿼리
FROM pg_stat_statements WHERE query NOT LIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC LIMIT 10;
```

풀스캔이 어디서 나는지는 이쪽이 빠르다.

```sql
SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup
FROM pg_stat_user_tables ORDER BY seq_tup_read DESC LIMIT 10;
```

## 정리

테스트는 실제 API로 데이터를 만든다. 정합성은 맞지만 전부 난수 조합이라, 남겨두면 인기 보드
후보에 섞이고 이후 측정치를 오염시킨다.

**비교 측정 전에는 반드시 지울 것.** 좋아요 이력이 계정에 누적되고 재계산 비용이 O(이력)이라,
계정을 재사용하면 출발점이 달라져 비교가 성립하지 않는다. 실제로 이것 때문에 개선을 퇴행으로
잘못 읽은 적이 있다(docs 4-7).

```bash
set -a && . ./crates_server.env && set +a
docker exec -i -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server \
  psql -U $POSTGRESQL_USERNAME -d crates -f - < load-test/cleanup.sql
```

## 기준선과 한계

```
backend 4 vCPU / 3GB, k6 VU 20, 좋아요 400건
  97 TPS   p95 221ms   p99 272ms   실패 0        (2026-09-15)
```

비교할 때 **같은 조건인지 먼저 확인할 것.** VU 수, 반복 수, board 테이블 크기, 1인당 좋아요
이력이 전부 결과를 바꾼다.

자원 상한이 생겨서 **같은 설정이면 같은 값이 나온다.** 다만 이것이 "운영에서 기대할 수치"는
아니다 — **부하 생성기가 여전히 같은 기계**에 있다. 그게 남은 가장 큰 오염이고, 컨테이너화로는
해결되지 않는다. 자세한 것은 [docs 2장](../docs/load-test-and-metrics.md).
