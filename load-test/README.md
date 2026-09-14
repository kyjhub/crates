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

- 앱이 `localhost:8080`에 떠 있을 것
- `crates_server.env`가 저장소 루트에 있을 것 (DB 관측에 쓴다)
- `k6` 설치 (`brew install k6`)

> **부하테스트는 빌드한 jar로 띄우고 재는 편이 낫다.** `./gradlew bootRun`은
> `spring-boot-devtools`가 활성화돼 재시작 클래스로더가 끼어 있어 운영과 다른 JVM이다.
> `bootJar`는 devtools를 제외한다.

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

```bash
set -a && . ./crates_server.env && set +a
docker exec -i -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server \
  psql -U $POSTGRESQL_USERNAME -d crates -f - < load-test/cleanup.sql
```

## 지금 수치의 한계

부하 생성기가 서버와 **같은 노트북**에 있고 자원 상한도 없다.
**절대 TPS는 쓸 수 없고**, 같은 조건의 상대 비교와 추세만 유효하다.
절대값이 필요해지면 앱을 compose에 올려 CPU·메모리를 제한하고, 생성기를 다른 기계로 빼야 한다.
