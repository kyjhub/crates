# Crates

책·영화·음악을 콘텐츠 8개로 구성된 **보드**에 모으고, 사용자의 좋아요 이력과 검색어를 바탕으로 보드를 추천하는 서비스의 백엔드입니다.

- 홈에서는 사용자 취향 벡터와 가까운 콘텐츠로 **추천 보드 4개**를 만들어 보여줍니다.
- 검색어를 입력하면 AI 서버가 검색어를 임베딩하고, 그 벡터와 가까운 콘텐츠로 **검색 보드**를 만듭니다.
- 보드 제목은 보드에 담긴 콘텐츠 8건의 **평균 벡터와 가장 가까운 query**로 정합니다.
- 좋아요를 누르면 좋아요한 보드들로 **취향 벡터를 다시 계산**해 다음 추천에 반영합니다.

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security(OAuth2 + JWT) |
| 저장소 | PostgreSQL 15(Flyway), Qdrant 1.14(벡터 검색), Redis(추천 캐시·OAuth 임시 토큰), MinIO(콘텐츠 이미지) |
| 관측 | Spring Boot Actuator, Micrometer(Prometheus), `pg_stat_statements` |
| 인프라·측정 | Docker Compose, k6 |

## 구조

```
                 ┌──────────── AI 서버(외부) ── 검색어 → 임베딩 벡터
                 │
클라이언트 ── backend(Spring Boot) ──┬── PostgreSQL : 콘텐츠·사용자·보드·좋아요·취향 벡터
                                    ├── Qdrant     : content_vector(콘텐츠 벡터), query_vector(보드 제목 후보)
                                    ├── Redis      : 사용자별 추천 결과 캐시, OAuth 임시 토큰
                                    └── MinIO      : 콘텐츠 이미지(브라우저가 직접 받음)
```

### 추천 흐름

1. 사용자 취향 벡터를 읽는다. 같은 버전(`user_vector.updated_at`)으로 계산해 둔 결과가 Redis에 있으면 Qdrant를 건너뛴다.
2. 없으면 Qdrant에서 취향 벡터와 가까운 콘텐츠 32건을 받아(벡터 포함) 8건씩 보드 4개로 나눈다.
3. 보드마다 콘텐츠 평균 벡터로 `query_vector`를 검색해 제목을 정한다. 4개를 **묶음 검색 한 번**으로 보낸다.
4. 같은 구성의 보드가 이미 저장돼 있으면 저장된 제목·좋아요 상태를 쓴다(PostgreSQL에서 매번 새로 읽는다).

### 취향 벡터 재계산

좋아요·취소가 커밋된 뒤 재계산 대기열에 넣는다. 같은 사용자의 요청은 합쳐 한 번만, 동시에 최대 2개만 돈다. 재계산은 트랜잭션 밖에서 Qdrant를 읽고 저장만 `UPDATE` 한 번으로 한다. 대기 중 유실된 재계산은 5분마다 도는 백필이 DB 상태로 찾아 다시 한다.

## 성능 개선

6개월 운영을 가정한 데이터(계정 1,200 / 보드 25만 / board_item 200만 / 좋아요 34만)로 측정했습니다.

**DB 쿼리** — 리포지토리 메서드 43개를 같은 조건에서 재고, 인덱스마다 이득(블록·p95)과 비용(크기·쓰기당 WAL·HOT 업데이트)을 비교해 6개만 남겼습니다.

| 쿼리 | 전 | 후 |
|---|---|---|
| 홈 인기 보드 | 8,242블록 / p95 39.7ms | 13블록 / 0.009ms |
| 보관함 전체 탭 (UNION ALL + 부분 인덱스) | 8,406블록 / 49.3ms | 99블록 / 0.20ms |
| 보드 signature 조회 (네이티브 쿼리에 상수 조건) | 8,163블록 / 11.4ms | 4블록 |

**API 부하 테스트** — 목표(p95 500ms, 검색 1.5초, 50 RPS)를 지키는 최소 자원을 찾기 위해 컨테이너마다 CPU 상한을 걸고, 도착률을 고정한 k6 부하를 단계별로 올려 측정했습니다.

| 조치 | 효과 |
|---|---|
| 재계산을 트랜잭션 밖으로 + 사용자별로 합쳐 동시 2개 | 좋아요 처리 5쌍/s → 30쌍/s 이상, 커넥션 대기 136 → 0 |
| open-in-view 끄기, Qdrant 호출을 트랜잭션 밖으로 | Qdrant가 포화돼도 다른 API p95 17ms 유지 |
| 보드 제목 검색 4번 → 묶음 검색 1번 | Qdrant CPU 약 1/3 감소, 추천 한계 50 → 75/s |
| 추천 결과 Redis 캐시(취향 벡터 버전으로 무효화) | 혼합 300/s(목표의 6배)에서 추천 p95 155ms |

결과적으로 **backend 2 / PostgreSQL 1 / Qdrant 2코어**로 검색을 포함한 혼합 150/s(목표의 3배)를 통과했습니다. Qdrant 호출 상한(bulkhead)과 요청 가상 스레드도 시도했지만, 조치별 기여도를 분리해 재고 반복·포화 측정으로 효과가 없음을 확인해 걷어냈습니다.

자세한 측정 방법과 결과는 [`load-test/README.md`](load-test/README.md), [`docs/load-test-and-metrics.md`](docs/load-test-and-metrics.md)에 있습니다.

## 실행

### 준비물

1. **환경변수 파일** — 저장소 루트에 `crates_server.env`를 만든다(git에 올리지 않는다).

   ```
   POSTGRESQL_USERNAME=
   POSTGRESQL_PASSWORD=
   JWT_SECRET_KEY=
   STORAGE_ACCESS_KEY=
   STORAGE_SECRET_KEY=
   GOOGLE_CLIENT_ID=
   GOOGLE_CLIENT_SECRET=
   KAKAO_CLIENT_ID=
   KAKAO_CLIENT_SECRET=
   NAVER_CLIENT_ID=
   NAVER_CLIENT_SECRET=
   ```

2. **시딩 데이터** — 용량이 커서 git에 올리지 않는다. `src/main/resources/data/`에 둔다.

   | 파일 | 내용 |
   |---|---|
   | `book.csv`, `movie.csv`, `music.csv` | 콘텐츠 원본 |
   | `book_vectors.csv`, `movie_vectors.csv`, `music_vectors.csv` | AI 서버가 만든 콘텐츠 벡터(768차원) |
   | `popular_top200_ids.csv` | 가입 직후 고를 취향 콘텐츠 후보(종류별 인기순 200개) |

3. **AI 서버** — 검색 보드에 필요하다. 주소는 `application.yaml`의 `ai.server.base-url`.

### 띄우기

```bash
docker compose --env-file crates_server.env up -d --build
```

처음 띄우면 Flyway가 스키마를 만들고 콘텐츠를 시딩한 뒤(V1~V9), 콘텐츠 벡터와 임시 query 벡터를 Qdrant에 적재한다. 4~5분 걸린다.

- Qdrant에는 볼륨이 없어 컨테이너를 다시 만들면 벡터가 사라진다. backend가 뜰 때 개수를 확인해 다시 적재한다.
- 실제 query 데이터가 들어오기 전까지는 콘텐츠 벡터를 복제한 임시 query를 쓴다(`QueryVectorStubLoader`). 들어오면 `ai.vectorstore.qdrant.query-stub.enabled`를 `false`로 바꾼다.
- 처음부터 다시 시작하려면 `docker compose down -v`로 볼륨까지 지운다.

| 서비스 | 포트 |
|---|---|
| backend | 8080 |
| PostgreSQL | 5432 |
| Qdrant | 6333(REST), 6334(gRPC) |
| Redis | 6379 |
| MinIO | 9000 |

### 부하 측정 구성

```bash
docker compose --env-file crates_server.env -f docker-compose.yml -f docker-compose.loadtest.yml up -d --build
./load-test/prepare.sh                                    # 6개월 운영 데이터 시딩
./load-test/run-sweep.sh mixed 25 50 75 100 150           # 도착률을 단계별로 올려 측정
```

자원 상한(`BACKEND_CPUS`, `PG_CPUS`, `QDRANT_CPUS`)과 시나리오 설명은 [`load-test/README.md`](load-test/README.md)에 있다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/oauth-token` | OAuth 로그인 후 임시 토큰을 JWT로 교환 |
| POST | `/api/auth/profile` | 가입 직후 프로필 입력 |
| POST | `/api/auth/refresh`, `/api/auth/logout` | 토큰 갱신, 로그아웃 |
| GET | `/api/users/me/onboarding` | 취향 콘텐츠를 골랐는지 |
| GET | `/api/contents/onboarding?type=` | 가입 직후 고를 취향 콘텐츠 후보(종류별) |
| POST | `/api/users/me/initial-contents` | 고른 콘텐츠로 첫 취향 벡터 만들기 |
| GET | `/api/boards/recommendation/user` | 홈 추천 보드 4개 |
| GET | `/api/boards/liked?n=` | 인기 보드 |
| GET | `/api/boards/mine?filter=&page=&size=` | 보관함(ALL / LIKED / CREATED) |
| GET | `/api/boards/list?boardId=` | 보드 상세 |
| GET | `/api/search/board?keyword=` | 검색 보드 |
| POST | `/api/boards` | 저장 안 된 보드를 고쳐 내 보드로 만들기 |
| PUT | `/api/boards/{boardId}` | 보드 수정(남의 보드면 복제본) |
| POST·DELETE | `/api/boards/{boardId}/likes` | 저장된 보드 좋아요·취소 |
| POST | `/api/boards/likes` | 저장 안 된 보드에 좋아요(저장 + 좋아요) |
| GET | `/api/contents/search?keyword=` | 콘텐츠 제목 검색(3글자 이상) |
| GET | `/api/contents/{contentId}`, `/api/contents/{dtype}/{contentId}` | 콘텐츠 요약, 상세 |

## 디렉터리

```
src/main/java/com/crates/crates
├── controller · service · repository · entity · DTO
├── qdrant        Qdrant 호출(검색·묶음 검색·조회·적재)
├── ai            AI 서버 임베딩 클라이언트
├── initializer   기동 시 콘텐츠 벡터·임시 query 벡터 적재
├── seed          Flyway Java 마이그레이션(콘텐츠·이미지·취향 후보 시딩)
├── scheduler     취향 벡터 백필, 만료 토큰 정리
└── jwt · oauth · user · config · Global
src/main/resources/db/migration   기능별 스키마(V1 콘텐츠, V2 사용자, V3 보드, V4 로그, V8 제목 검색 인덱스)
load-test/                        시더, k6 시나리오, 측정 스크립트
docs/                             보드 스키마 설계, 부하 측정 기록
```
