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

## 데이터 준비와 부하 생성은 다른 도구다

```bash
./load-test/seed.sh [계정수] [보드수] [계정당좋아요]
```

`seed.sh`가 **측정하려는 상태**를 만들고, k6는 **그 상태에 부하**를 건다. k6는 계정도 보드도
만들지 않는다 — 없으면 "seed.sh를 먼저 돌리라"며 바로 실패한다.

좋아요는 보드 **전 구간에 고르게** 흩뿌린다. 앞에서부터 집으면 모든 사용자가 같은 앞쪽
보드만 좋아요하고 나머지는 0건이 되는데, 그러면 조인 비용이 실제보다 작게 나온다 —
실측에서 몰렸을 때 58버퍼, 흩어졌을 때 480버퍼로 **8배 차이**가 났다.

`seed.sh`가 매번 분포를 확인하고, 쏠리면 **종료코드 1로 멈춘다.**

```
좋아요 분포(보드 5구간): 100 100 100 100 100   받은 보드 500/500      정상
좋아요 분포(보드 5구간): 500 0 0 0 0   받은 보드 50/500               ⚠️ 멈춤
```

이 왜곡은 한 번 발견해 문서에 적어두고도 시더를 새로 짜면서 그대로 반복했다.
주석만으로는 부족해서, 사람이 기억하지 않아도 드러나도록 검증을 붙였다.

```bash
./load-test/seed.sh 20 600 0        # 좋아요 없는 계정 20개 + 보드 600개
./load-test/seed.sh 1 1000 200      # 이력 200건짜리 계정 하나 (프로파일링용)
./load-test/seed.sh 200 20000 180   # 플래너가 인덱스를 고르기 시작하는 규모
```

### 얼마나 크게 만들어야 하나

축마다 의미가 다르다. 전부 키우는 것이 아니라 **재려는 것에 맞는 축**을 키운다.

| 축 | 왜 필요한가 | 권장 |
|---|---|---:|
| `content` | 이미 191,239건 — 그대로 둔다 | 고정 |
| `board` | 수백 행에서는 플래너가 언제나 seq scan을 고른다. 운영 계획을 볼 수 없다 | 20,000+ |
| `board_feedback` | 보관함·재계산 비용이 여기 비례 | 수만~ |
| **1인당 좋아요** | **전체 행 수와 다른 축이다.** gRPC 4MB 버그는 1인당 170건에서 터졌다 | 150~200 |

보드 상한은 `콘텐츠 - 7×stride`다. 콘텐츠 191,239건 기준 약 184,000개까지 만들 수 있다.
보드 40,000개 + board_item 320,080건을 만드는 데 **3.7초** 걸린다.

> 크기로 해결되지 않는 것도 있다. `pg_stat_user_indexes.idx_scan`으로 확인하는 "이 인덱스가
> 실제로 쓰이는가"는 규모와 무관하고, SQL이 아닌 비용(Qdrant 왕복, 벡터 연산)은 데이터를
> 아무리 키워도 `pg_stat_statements`에 나타나지 않는다.

대부분을 SQL로 꽂는다. API로 좋아요 200건을 만들면 요청 200번 + 비동기 재계산 200번이라
수십 초가 걸리고, **준비 자체가 시스템을 데워 측정 조건을 바꾼다.** SQL이면 1초 안쪽이다.

> BCrypt 해시와 초기 취향 벡터는 SQL로 만들 수 없어, 기준 계정(`loadtest_seed`) 하나만
> API 회원가입으로 만들고 나머지는 거기서 복사한다. 비밀번호는 전부 `Loadtest!234`.

**왜 나눴나** — 그동안 k6가 데이터 생성까지 겸했다. 그래서 실행마다 상태가 누적돼 출발점이
달라졌고, 그것이 "개선을 퇴행으로 잘못 읽은" 원인이었다(아래 4-9 참고).

## 실행

```bash
./load-test/run-measure.sh <시나리오> [측정횟수]
```

스크립트가 **준비 확인 → 초기화·재시딩 → 워밍업 1회(버림) → 측정 N회 → 중앙값**을 한다.
매 측정 전에 `cleanup.sql` + `seed.sh`로 출발 상태를 똑같이 되돌린다.

| 시나리오 | 바뀌는 변수 | 쓸 곳 |
|---|---|---|
| `like-new-board` | board 행 수 **와** 좋아요 이력 (둘 다) | 코드 변경 전후 정상 상태 처리율 비교 |
| `board-growth` | **board 행 수만** (좋아요 후 즉시 취소) | 보드가 많아질 때 조회·INSERT 경로 |
| `like-history` | **좋아요 이력만** (미리 만든 보드 풀에 좋아요) | 재계산이 O(이력)인 것의 영향 |

```bash
# 기본: 코드 변경 전후 비교
USERS=20 ITERATIONS=600 ./load-test/run-measure.sh like-new-board 3

# board가 누적되는 효과 (--keep으로 매 실행 초기화를 건너뛴다)
USERS=20 ITERATIONS=600 ./load-test/run-measure.sh board-growth 5 --keep

# 이력이 쌓일 때 재계산 비용 — 실행 후 앱 지표를 함께 볼 것
USERS=10 ITERATIONS=600 POOL=500 SEED="10 600 0" ./load-test/run-measure.sh like-history 3

# 이미 이력 150건인 상태에서 시작
SEED="10 600 150" ./load-test/run-measure.sh like-history 3
```

환경변수: `USERS`, `ITERATIONS`, `POOL`(like-history), `SEED`("계정수 보드수 계정당좋아요"),
`BASE_URL`, `MAX_CONTENT_ID`(기본 183136).

### 왜 이런 모양인가

**워밍업** — 첫 실행은 정상 상태보다 **50% 느리다.** JVM JIT 때문이고 DB를 비워도 안 풀린다.
같은 조건 7회 반복 실측:

```
1회 104.0/s   2회 154.9   3회 173.2   4회 197.2   5회 197.3   6회 206.7   7회 209.7
```

4회차부터 수렴한다. **앱을 재시작하면 다시 식는다**(재시작 직후 113.3).

**반복과 중앙값** — 정상 상태에서도 실행 간 ±6%가 흔들린다. 1회 측정으로는 아무것도 말할 수
없다. 스크립트가 퍼짐을 함께 출력하니, **두 설정의 차이가 퍼짐보다 작으면 유의하지 않다.**

**변수 분리** — `like-new-board`는 board와 이력을 동시에 늘린다. "규모가 커지면 무엇이
느려지나"를 이걸로 물으면 답할 수 없다. `board-growth` / `like-history`를 쓸 것.

**처리율은 `iterations.rate`** — `http_reqs`에는 `setup()`의 회원가입·로그인이 섞여 약 10%
부풀려진다(실측 134.6 vs 122.4).

**준비 확인** — `/actuator/health` 응답은 측정 준비 완료가 **아니다.** Spring Boot는
`ApplicationRunner`를 "Started" 로그 이후에 돌리고, `seed completed` 이후에도 Qdrant가
HNSW를 백그라운드로 짓는다(실측 35초, CPU 815%). 스크립트가 Qdrant CPU가 20% 아래로
떨어질 때까지 기다린다.

## 느린 쿼리·메서드 찾기 (부하가 필요 없다)

**이건 부하테스트가 아니다.** 요청 몇 번이면 된다. `pg_stat_statements`는 누적 집계라
6요청만으로도 어느 쿼리가 비싼지 드러난다.

```bash
# 1. 상태 준비
./load-test/seed.sh 10 500 50

# 2. 누적 초기화
docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server \
  psql -U $POSTGRESQL_USERNAME -d crates -c "SELECT pg_stat_statements_reset();"

# 3. 재고 싶은 엔드포인트를 몇 번 호출   ← 이 단계가 빠지면 아무것도 안 잡힌다
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"loginId":"loadtest_u0","pwd":"Loadtest!234"}' | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")
for i in 1 2 3; do curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" \
  localhost:8080/api/boards/recommendation/user; done

# 4. 읽기 (아래 "읽는 법" ③)
```

**3단계를 빠뜨리기 쉽다.** `seed.sh`는 SQL을 직접 꽂으므로 레포지토리 메서드를 거치지 않는다.
시딩만 하고 조회하면 결과가 비어 있다.

### pg_stat_statements가 못 보는 것

레포지토리 메서드를 지정할 필요는 없다 — DB에 도달한 **모든 SQL**을 자동으로 잡는다.
다만 두 가지는 안 보인다.

**어느 Java 메서드가 날린 쿼리인지** 알려주지 않는다. 쿼리 텍스트를 보고 직접 찾아야 한다.

**SQL이 아닌 것은 전혀 안 보인다.** 이 프로젝트에서는 이게 결정적이다 — 취향 벡터 재계산
1건이 24.56ms인데 그중 SQL은 **0.17ms(0.7%)**뿐이고, 나머지는 Qdrant 왕복과 768차원 벡터
연산이다. `pg_stat_statements`만 보면 가장 비싼 곳을 통째로 놓친다.

| 도구 | 보는 것 | 잴 곳 지정 |
|---|---|---|
| `pg_stat_statements` | 모든 SQL | 불필요 |
| Micrometer Timer | 지정한 메서드 | **필요** |
| JFR (`jcmd 1 JFR.start`) | JVM 전체 핫스팟 | 불필요, 대신 해석이 필요 |

```bash
# 어디가 느린지 모를 때: JDK 내장 프로파일러
docker exec backend_server jcmd 1 JFR.start duration=60s filename=/tmp/rec.jfr
docker cp backend_server:/tmp/rec.jfr .     # JDK Mission Control으로 연다
```

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
backend 4 vCPU / 3GB, VU 20, like-new-board 300회, SEED="20 600 0"
워밍업 후 2회 중앙값 → 164.6 /s   p95 72ms   퍼짐 4%        (2026-09-19)
```

> 2026-09-19 이전 수치(97 TPS 등)는 워밍업 없이 1회 측정한 것이라 이 값과 비교할 수 없다.

비교할 때 **같은 조건인지 먼저 확인할 것.** VU 수, 반복 수, board 테이블 크기, 1인당 좋아요
이력이 전부 결과를 바꾼다.

자원 상한이 생겨서 **같은 설정이면 같은 값이 나온다.** 다만 이것이 "운영에서 기대할 수치"는
아니다 — **부하 생성기가 여전히 같은 기계**에 있다. 그게 남은 가장 큰 오염이고, 컨테이너화로는
해결되지 않는다. 자세한 것은 [docs 2장](../docs/load-test-and-metrics.md).
