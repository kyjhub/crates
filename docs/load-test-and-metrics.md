# 부하테스트 대상과 취향 벡터 지표

무엇을 부하테스트할지, 취향 벡터 정책의 효과를 무엇으로 잴지 정리한다.
"전부 측정한다"가 아니라 **지금 실제로 위험한 곳**과 **지금 재도 뜻이 있는 지표**만 남긴다.

- 계획 작성: 2026-09-14
- 1차 실측: 2026-09-14 (2장·3장에 반영). 아직 안 잰 항목은 그렇다고 표시했다.

---

## 1. 전제 정정 — 배치는 구현된 적이 없다

"새벽 배치가 채우던 것을 실시간으로 바꿨다"는 표현이 커밋 메시지에 남아 있지만,
**그 배치는 구현된 적이 없다.**

- 스케줄러는 `RefreshTokenCleanupScheduler` 하나뿐이다.
- 이전 상태는 `UserVector`에 `new float[vectorDimension]`, 즉 **0 벡터**였다.
- 0 벡터는 Cosine 거리가 정의되지 않아 추천이 성립하지 않는다.

즉 정책 변경은 "느린 배치 → 빠른 실시간"이 아니라 **"동작하지 않던 것 → 동작하는 것"**이다.
비교 대상이 없으므로 "이전 대비 개선율" 같은 숫자는 만들 수 없고, 만들면 거짓이 된다.
자세한 것은 5장.

---

## 2. 1차 실측 결과 — 예상과 결론이 뒤집혔다

**1순위로 지목했던 것은 문제가 아니었고, 2순위가 진짜 병목이었다.**

| 항목 | 사전 예상 | 실측 결과 |
|---|---|---|
| 좋아요 재계산이 이력에 비례 | 위험 | **맞음.** 보드 1건당 약 1ms |
| 비동기 적체 / 커넥션 고갈 | 1순위 위험 | **틀림.** 적체 없음, 커넥션은 대부분 놀고 있었다 |
| signature 조회가 부분 인덱스를 못 탐 | "가능성" | **맞음. 그리고 이것이 실제 병목이다** |

부하가 올라갈 때 무너진 것은 비동기 경로가 아니라 **동기 경로**였다.
동시성을 올릴수록 처리량이 **떨어지는** 전형적인 혼잡 붕괴가 나왔다.

### 테스트 환경 (숫자를 읽을 때의 전제)

노트북 한 대에서 앱·PostgreSQL·Qdrant·MinIO가 모두 돌고, **부하 생성기까지 같은 기계**에 있다.
따라서 **절대 TPS는 의미가 없다.** 같은 조건에서의 상대 비교와 추세만 유효하다.

---

## 3. 부하테스트 대상

부하테스트 도구는 도입되어 있지 않다(k6 / gatling / jmeter 없음). 1차 측정은 Python
표준 라이브러리로 만든 임시 드라이버로 했다(7장 참고).

| 순위 | 대상 | 상태 |
|---|---|---|
| 1 | **보드 signature 조회** (추천 조회 · 좋아요 저장) | 측정 완료 — 병목 확인, 해법 검증 |
| 2 | 좋아요 → 취향 벡터 재계산 | 측정 완료 — 현 규모에선 문제 없음, 이력 증가가 잠재 위험 |
| 3 | 보관함 전체 탭 | 미측정 |
| 4 | 콘텐츠 제목 검색 | 미측정 |

### 3-1. 보드 signature 조회 (1순위) — 확인된 병목

`BoardRepository.findActiveByTypeAndSignature`는 추천 보드를 내려줄 때
`resolveGeneratedBoard`가 **보드마다** 부르므로 홈 진입 1회에 4번, 그리고 좋아요로 보드를
저장할 때마다 1번 호출된다.

```sql
-- V5: board_type 이 부분 인덱스의 술어에 있다
CREATE UNIQUE INDEX uk_board_ai_signature ON board (content_signature)
    WHERE board_type = 'AI_RECOMMEND' AND deleted_at IS NULL;
```
```java
// board_type 을 바인드 파라미터로 넘긴다
"WHERE b.boardType = :boardType AND b.contentSignature = :signature AND b.deletedAt IS NULL"
```

PostgreSQL이 제네릭 플랜을 쓰면 `board_type = $1`이 `= 'AI_RECOMMEND'`를 함의한다고 증명하지
못해 **부분 인덱스를 쓰지 못한다.** 실측(보드 3,128행):

| 조건 | 실행 계획 | 버퍼 | 소요 |
|---|---|---:|---:|
| 리터럴 | `Index Scan using uk_board_ai_signature` | 2 | 0.012ms |
| 바인드 + 제네릭 플랜 | **`Seq Scan`** (3,128행 필터) | 74 | 0.607ms |

보드 수에 비례해 나빠진다. **통제 실험** — 동일 조건(좋아요 100건 / 워커 20)에서
`board` 테이블만 620행 → 3,128행으로 커졌을 때:

| 경로 | TPS | p50 |
|---|---:|---:|
| 새 보드 생성 + 좋아요 (signature 조회 있음) | **40** (620행 시점 107) | 568ms |
| 기존 보드 좋아요 (signature 조회 없음) | 62 | 344ms |
| 추천 보드 조회 (읽기 전용) | 138 | 96ms |

동시성을 올리면 처리량이 오히려 떨어진다.

| 워커 | 좋아요 | TPS | HTTP p95 |
|---:|---:|---:|---:|
| 20 | 100 | 107 | 281ms |
| 40 | 200 | 92 | 631ms |
| 60 | 400 | 66 | 1,289ms |
| 80 | 600 | 46 | 2,591ms |
| 100 | 800 | 31 | **4,695ms** |

#### JPQL에 enum을 직접 써도 소용없다

`findActiveUserBoardBySignature`처럼 `b.boardType = com.crates...BoardType.USER_CUSTOM`으로
리터럴을 박는 선례가 코드에 있지만, **Hibernate 6가 그것도 파라미터로 바꾼다.**
테스트 중 로그 집계: `board_type='USER_CUSTOM'` 0회, `board_type=?` 6,900회.
따라서 `uk_board_user_signature`도 같은 문제를 안고 있다.

#### 해법 (검증 완료)

V7에서 `rating`에 한 것과 같다. **조건 컬럼을 부분 인덱스 술어가 아니라 인덱스 컬럼으로 옮긴다.**

```sql
CREATE INDEX idx_board_signature_lookup ON board (board_type, content_signature)
    WHERE deleted_at IS NULL;
```
```
Index Scan using idx_board_signature_lookup   buffers 2   0.031ms   ← 제네릭 플랜에서도 탄다
```

트랜잭션 안에서 만들어 `EXPLAIN`으로 확인하고 롤백해 검증했다.

- `deleted_at IS NULL`은 부분 술어로 남겨도 된다. 쿼리에 **상수**로 박혀 있어 플래너가 증명할 수
  있다. **파라미터에 의존하는 술어만** 문제다.
- 유니크 제약(`uk_board_ai_signature`, `uk_board_user_signature`)은 그대로 둔다. 제약의 의미가
  부분적이라(삭제되지 않은 AI_RECOMMEND 안에서만 유일) 일반 유니크로 바꿀 수 없다.
  조회용 인덱스를 **따로** 추가하는 형태여야 한다.

### 3-2. 좋아요 → 취향 벡터 재계산 (2순위) — 현 규모에선 문제 없음

**비용이 이력에 비례하는 것은 사실이다.** `recalculateFor`가 매번 좋아요 집합 전체를 다시 읽는다.

| 좋아요 이력 | 재계산 반영 지연 | 좋아요 HTTP |
|---:|---:|---:|
| 0 | 32ms | 52ms |
| 10 | 19ms | 34ms |
| 25 | 35ms | 25ms |
| 50 | 49ms | 20ms |
| 100 | **107ms** | 28ms |

**보드 1건당 약 1ms.** 좋아요 1,000건인 사용자는 좋아요를 누를 때마다 1초짜리 재계산이 돈다.
HTTP 응답은 이력과 무관하게 평평하다 — 비동기 설계는 의도대로 동작한다.

**그러나 사전에 1순위로 지목했던 위험(무제한 팬아웃 → 커넥션 고갈)은 재현되지 않았다.**

| 이력/인 | 좋아요 | 워커 | 배수 시간 | 최대 적체 |
|---:|---:|---:|---:|---:|
| 41 | 100 | 20 | 0.07s | 0명 |
| 56 | 400 | 60 | 0.12s | 0명 |
| 106 | 800 | 100 | 0.41s | 1명 |

- 재계산 실패 0건, 커넥션 타임아웃 0건, 벡터 유실 0명
- DB 커넥션은 평균 9.9개가 `idle`, `active` 최대 2개 — **놀고 있었다**
- 즉 동시 실행 상한이나 사용자별 디바운스는 **지금 필요하지 않다.**

다시 볼 조건: 1인당 좋아요가 수백 건을 넘어가면 재계산 1건이 수백 ms가 되고, 그때는
3-1의 동기 경로와 커넥션을 다투기 시작한다. **1인당 평균 좋아요 수를 지표로 감시할 것.**

### 3-3. 보관함 전체 탭 (미측정)

```sql
ORDER BY COALESCE(f.created_at, b.created_at) DESC, b.id DESC
```

표현식 정렬이라 인덱스로 해결할 수 없다. 매 페이지마다 매칭 집합 전체를 정렬하고, 오프셋
페이징이라 뒷페이지일수록 건너뛰는 비용이 는다. 보드 1건에 콘텐츠 8건이 딸려 오므로
`size=20`이면 콘텐츠 160행을 조립한다.

설계 당시 **"개인 보관함은 많아야 수백 건"**을 전제하고 오프셋을 택했다. 이 테스트의 목적은
그 전제 확인이다. 깨지면 키셋(커서) 페이징으로 옮겨야 하는데 `COALESCE` 정렬 키가 까다롭다.

### 3-4. 콘텐츠 제목 검색 (미측정)

단건 성능은 V6 마이그레이션 주석에 실측돼 있다.

```
콘텐츠 191,239건 / PostgreSQL 15
"har" (3,579건 매칭) 17ms · "harry potter" 1ms · "the" (62,004건 매칭) 116ms
```

그러므로 **동시 실행만** 확인하면 된다. 자동완성이라 디바운스가 있어도 요청이 잦고,
트라이그램 매칭은 CPU 바운드라 동시성이 올라가면 서로 잡아먹는다.

### 3-5. 지금 재면 안 되는 것 — 검색 보드

`/api/search/board`는 미룬다. 지금은 `ai.server.stub.enabled: true`라 로컬에서 난수를
만들지만, AI 서버가 연결되면 외부 HTTP 호출(connect 2s / read 3s)이 된다.
프로파일이 완전히 달라 지금 재면 틀린 것을 재게 된다.

### 3-6. 부수 발견 — 로그인 실패가 500

```
ERROR [SYSTEM ERROR] URI: /api/auth/login | 자격 증명에 실패하였습니다.
```

`AuthenticationException`이 `GlobalExceptionHandler`에 없어 500으로 나간다.
커밋 `b3dc6a7`에서 고친 것과 같은 종류다.

---

## 4. 측정 방법과 함정

### 4-1. 재계산 지연을 재는 법

앱에 계측이 없으므로 DB의 두 타임스탬프 차이로 잰다.

```
user_vector.updated_at - board_feedback.created_at   =  큐 대기 + 재계산 소요
```

둘 다 앱이 `LocalDateTime.now()`로 찍으므로 시계 오차가 없다.
**이 값이 음수면 재계산이 유실된 것이다.**

### 4-2. 내가 틀렸던 측정 — 기록해 둘 것

1차 시도에서 "1.3초 발사 → **70초** 배수"가 나왔고, 하마터면 심각한 적체로 보고할 뻔했다.
실제로는 **측정 코드가 만든 값**이었다.

- 사용자별로 "`updated_at`이 3초간 안 변하면 잠잠"이라고 판정했고
- 그 판정을 20명에게 **순차로** 돌렸다 → 20 × 3초 = **60초가 바닥으로 깔린다**

집계 쿼리 하나로 "밀린 사용자가 몇 명인가"를 판정하도록 바꾸니 실제 배수는 0.1초 안팎이었다.

```sql
-- 이 값이 0이 되는 순간이 배수 완료
SELECT count(*) FROM user_vector uv
  JOIN users u ON u.id = uv.user_id
  JOIN LATERAL (SELECT created_at FROM board_feedback
                WHERE user_id = uv.user_id AND rating = 'LIKE'
                ORDER BY created_at DESC LIMIT 1) f ON true   -- idx_feedback_user_recent
  WHERE u.login_id LIKE 'loadtest_%' AND uv.updated_at < f.created_at;
```

교훈: **폴링 간격과 안정 판정 시간이 측정하려는 값보다 크면 측정기가 결과를 만든다.**
비동기 작업의 지연을 잴 때는 관측 자체의 바닥값을 먼저 계산해 볼 것.

### 4-3. 인덱스 후보를 안전하게 검증하는 법

PostgreSQL은 DDL도 트랜잭션이다. 스키마를 건드리지 않고 후보 인덱스를 시험할 수 있다.

```sql
BEGIN;
CREATE INDEX ... ;
ANALYZE board;
SET plan_cache_mode = force_generic_plan;   -- 바인드 파라미터 상황을 강제 재현
PREPARE q(varchar, varchar) AS SELECT ... WHERE board_type = $1 AND content_signature = $2 ...;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE q('AI_RECOMMEND', '...');
ROLLBACK;
```

`force_generic_plan`이 핵심이다. 그냥 `EXPLAIN`을 하면 커스텀 플랜이 잡혀 **문제가 재현되지
않는다.** 실제 앱은 실행 횟수에 따라 제네릭 플랜으로 넘어가므로 증상이 간헐적으로 보인다.

---

## 5. 취향 벡터 정책의 효과 측정

### 5-1. "이전 대비 개선"은 잴 수 없다

1장에서 정리한 이유에 하나가 더 겹친다 — **콘텐츠 벡터가 전부 더미 난수다**
(`ContentVectorSeeder`). 의미 없는 공간에서 추천 품질을 재면 숫자는 나오지만 뜻이 없다.

그래서 **지금 잴 것(메커니즘)**과 **AI 서버 연결 후에 잴 것(품질)**을 나눈다.

### 5-2. 지금 잴 수 있는 것 — 산식이 설계대로 도는가

**① 반영 지연** (4-1의 식). 정책 변경의 본질이 "지연"이므로 헤드라인 지표다.

> 실측: 무부하 p50 36ms. 좋아요 800건 폭주 상황에서도 배수 0.4초 이내.
> Qdrant 버전 불일치를 고치기 전에는 이 값이 **음수**였다(벡터가 가입 시각에 멈춰 있었다).

**② 재계산 비용 곡선.** 3-2 표.

> **구현됨 (2026-09-14).** `UserVectorMetrics`가 아래 넷을 내보낸다.
> 조회는 `GET /actuator/metrics/{이름}` — JWT 필요.
>
> | 지표 | 종류 | 뜻 |
> |---|---|---|
> | `crates.user.vector.stale.users` | Gauge | 마지막 좋아요보다 벡터가 오래된 사용자 수. **0이 정상** |
> | `crates.user.vector.recalculation` | Timer | 재계산 소요 시간. `result` 태그 = updated / unchanged / skipped |
> | `crates.user.vector.recalculation.boards` | Summary | 재계산 1건이 훑은 보드 수 |
> | `crates.user.vector.recalculation.failures` | Counter | 예외로 끝난 횟수 |
>
> 검증: 좋아요 3건 → `recalculation` COUNT 3 / TOTAL_TIME 0.445s / tag `updated`,
> `boards` TOTAL 465(평균 155). **보드당 0.96ms로 3-2 실측(약 1ms)과 일치한다.**
>
> 주의 둘. Timer는 메서드 본문까지만 재고 **커밋은 빠진다** — 끝에서 끝까지는 4-1의 식으로 봐야 한다.
> failures는 커밋 단계 예외를 못 센다(리스너 try/catch 밖에서 터진다) — `stale.users`와 함께 볼 것.
> 실패 카운터만 기동 시 0으로 미리 등록했다. Micrometer는 첫 사용 때 지표를 만들어서,
> 그냥 두면 "실패 없음"과 "계측 안 붙음"이 똑같이 '지표 없음'으로 보인다.

Prometheus 레지스트리는 없어 `/actuator/metrics` 인메모리 조회만 가능하다.

**③ 좋아요 한 번의 이동량.** `cos(V_이전, V_이후)`. 0에 가까우면 정책이 사실상 무효고,
1에 가까우면 과반응이다. `ai.user-vector.decay-alpha` 조정 시의 회귀 지표.

**④ 추천 결과 교체율.** 좋아요 전후 top-32의 겹침(overlap@32).

> ③④는 품질이 아니라 **산식 검증**이라 더미 벡터로도 유효하다.
> ④가 없어서 Qdrant 버전 불일치를 나흘 늦게 발견했다. 벡터가 전혀 변하지 않는데도
> 우는 지표가 하나도 없었다. **우선순위는 ④가 가장 높다.**

### 5-3. AI 서버 연결 후 — 품질

**오프라인 리플레이**가 가장 실용적이다. 배치가 구현된 적 없어도 흉내 내면 된다.

```
좋아요 N건 이상인 사용자를 시간순 정렬
  → 마지막 k건을 홀드아웃
  → 앞의 (N-k)건으로 벡터를 두 방식으로 계산
       (a) 마지막 하루 경계까지만 반영    ← 배치 시뮬레이션
       (b) 전부 반영                     ← 현행 실시간
  → 홀드아웃 보드의 콘텐츠가 top-K에 얼마나 들어오는가: Recall@K / NDCG 비교
```

핵심은 **산식을 동일하게 두고 반영 시점만 다르게** 하는 것이다. 그래야 "실시간"이라는
변수 하나만 분리된다.

**온라인 지표**(추천 노출 대비 좋아요율)는 분모가 없다. 5-4가 선행되어야 한다.

### 5-4. 선행 조건 — SystemLog 배선

노출 로그를 담을 자리는 **이미 설계돼 있으나 한 줄도 쓰이지 않는다.**

- `SystemLog`: `timestamp`, `userId`, `eventType`, `payload`(jsonb)
- `EventType`: `CLICK_BOARD`, `CLICK_CONTENT`, `UPDATE_BOARD`, `LIKE_BOARD`
- `SystemLogRepository`는 존재하지만 **어디서도 주입되지 않는다**

`userId`를 연관관계가 아니라 raw id로 들고 있어 탈퇴 후에도 남고, payload가 jsonb라 확장된다.
설계는 끝났고 배선만 빠졌다. AI 서버 연결 **전에** 해두어야 연결 직후부터 비교할 데이터가 쌓인다.

---

## 6. 다음 할 일

1. **`idx_board_signature_lookup` 마이그레이션(V8).** 3-1. 해법까지 검증돼 있어 바로 적용 가능.
   적용 후 3-1의 통제 실험을 다시 돌려 TPS가 회복되는지 확인한다.
2. ~~재계산 계측~~ — **완료(2026-09-14).** `UserVectorMetrics` 참고(5-2 ②).
   남은 것은 **추천 결과 교체율(5-2 ④)**이다. 우선순위가 가장 높은데 아직 없다.
3. **로그인 실패 500 수정** (3-6).
4. `SystemLog` 배선 (5-4). AI 서버 연결 전에.
5. 나머지 부하테스트 (3-3 보관함 → 3-4 검색).

---

## 7. 미결정

- **부하테스트 코드의 거처.** 1차 측정은 Python 표준 라이브러리 임시 스크립트로 했고
  저장소에 없다. 재현하려면 저장소에 두어야 한다(`load-test/` 등). 도구를 k6로 갈아탈지도 함께 결정.
- **격리된 측정 환경.** 지금은 부하 생성기가 서버와 같은 기계에 있어 절대 수치를 못 쓴다.
- **목표 수치.** 동시 사용자 규모 가정이 없어 합격선을 정하지 못했다.
  3-1을 고친 뒤의 값을 기준선으로 삼는 편이 낫다.
- 오프셋 → 키셋 페이징 전환 시점 (3-3).

---

## 8. 확인한 근거

| 주장 | 확인 방법 |
|---|---|
| 배치는 구현된 적 없음 | `grep -rln "JobBuilder\|StepBuilder\|@Scheduled" src/main/java` → 스케줄러 1개 |
| 이전 상태가 0 벡터 | `git log -p --all -S "0 벡터"` → `.userVector(new float[vectorDimension])` |
| Hikari 기본 10 | `application.yaml`에 `spring.datasource.hikari` 항목 없음 |
| 커넥션이 병목이 아니었음 | 폭주 중 `pg_stat_activity` 샘플링 → `idle` 평균 9.9 / `active` 최대 2 |
| 부분 인덱스가 안 먹음 | `SET plan_cache_mode = force_generic_plan` + `PREPARE`/`EXPLAIN` → Seq Scan |
| enum 리터럴이 파라미터로 바뀜 | 앱 로그 집계 → `board_type='USER_CUSTOM'` 0회, `board_type=?` 6,900회 |
| 대체 인덱스가 먹음 | `BEGIN; CREATE INDEX; EXPLAIN; ROLLBACK;` → Index Scan, buffers 74→2 |
| 재계산 실패·유실 0건 | 앱 로그에 재계산 오류 0건 + 4-2의 집계 쿼리 0명 |
| `SystemLog` 미사용 | `grep -rn "SystemLogRepository" src/main/java` → 선언 파일 외 참조 0건 |
| 콘텐츠 191,239건 | Qdrant `content_vector` points_count, V6 마이그레이션 주석 |
| pg_trgm 실측치 | `V6__ContentTitleSearch.sql` 주석 |

관련 문서: [board-schema.md](board-schema.md)
