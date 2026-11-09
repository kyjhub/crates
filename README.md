# Crates

책·영화·음악을 콘텐츠 8개로 구성된 **보드**에 모으고, 사용자의 좋아요 이력과 검색어를 바탕으로 보드를 추천하는 서비스의 백엔드입니다.

- 홈에서는 사용자 취향 벡터 근처에서 **서로 다른 제목 4개**를 고르고, 제목마다 어울리는 콘텐츠 8건을 담아 **추천 보드 4개**를 보여줍니다.
- 검색어를 입력하면 AI 서버가 검색어를 임베딩하고, 그 벡터와 가까운 콘텐츠로 **검색 보드**를 만듭니다. 보드 제목은 검색어입니다.
- 좋아요를 누르면 좋아요한 보드들로 **취향 벡터를 다시 계산**해 다음 추천에 반영합니다.
- 추천·검색 보드는 좋아요를 눌러야 저장됩니다. 화면에 내려간 보드에는 서명된 토큰이 붙어, 좋아요할 때 서버가 그 토큰 안의 제목·구성으로만 저장합니다.

서비스 주소: https://crates24.com · 프론트엔드: [kyjhub/crates_frontend](https://github.com/kyjhub/crates_frontend)

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security(OAuth2 + JWT) |
| 저장소 | PostgreSQL 15(Flyway), Qdrant 1.14(벡터 검색), Redis(추천·검색어 임베딩 캐시, OAuth 임시 토큰), S3(콘텐츠 이미지, 로컬은 MinIO) |
| 관측 | Spring Boot Actuator, Micrometer(Prometheus), `pg_stat_statements`, CloudWatch |
| 인프라 | AWS(CloudFront, ALB, ECS Fargate ARM64, RDS, ElastiCache, EC2), Terraform, GitHub Actions(OIDC) |
| 측정 | k6, Docker Compose |

## 구조

```
                         ┌── S3 + CloudFront : 프론트 정적 파일, 콘텐츠 이미지
브라우저 ── CloudFront ──┤
                         └── /api, /oauth2 ── ALB ── backend(ECS Fargate, Spring Boot)
                                                       ├── PostgreSQL(RDS) : 콘텐츠·사용자·보드·좋아요·취향 벡터·행동 로그
                                                       ├── Qdrant(EC2)     : content_vector(콘텐츠), query_vector(보드 제목 후보)
                                                       ├── Redis(ElastiCache): 추천 결과·검색어 임베딩 캐시, OAuth 임시 토큰
                                                       └── AI 서버(외부, Tailscale 중계) : 검색어 → 임베딩 벡터
```

### 홈 추천 흐름

1. 사용자 취향 벡터를 읽는다. 같은 버전(`user_vector.updated_at`)으로 계산해 둔 결과가 Redis에 있으면 Qdrant를 건너뛴다.
2. 취향 벡터와 가까운 query 200개를 받아, 이미 고른 제목과 비슷할수록 벌칙을 주며(MMR) **서로 다른 제목 4개**를 고른다.
3. 보드마다 "제목 70% + 취향 30%" 지점과 가까운 콘텐츠를 책 4 · 음악 2 · 영화 2씩 담는다. 4개를 **묶음 검색 한 번**으로 보내고, 앞 보드에 담긴 콘텐츠는 뒤 보드에 다시 담지 않는다.
4. 같은 구성의 보드가 이미 저장돼 있으면 저장된 제목·좋아요 상태를 쓴다(PostgreSQL에서 매번 새로 읽는다).

Qdrant 검색은 모두 트랜잭션 밖에서 한다. DB 커넥션을 쥔 채 Qdrant를 기다리지 않기 위해서다.

### 취향 벡터 재계산

좋아요·취소가 커밋된 뒤 재계산 대기열에 넣는다. 같은 사용자의 요청은 합쳐 한 번만, 동시에 최대 2개만 돈다. 재계산은 트랜잭션 밖에서 Qdrant를 읽고 저장만 `UPDATE` 한 번으로 한다. 대기 중 유실된 재계산은 5분마다 도는 백필이 DB 상태로 찾아 다시 한다.
좋아요 직후 홈 추천이 바로 바뀌지 않는 것은 의도된 동작이다(재계산이 끝나고 다음에 홈에 들어올 때 반영).

## 성능 개선

### DB 쿼리

6개월 운영을 가정한 데이터(계정 1,200 / 보드 50만 / board_item 400만 / 좋아요 70만, 시딩 v2)에서 리포지토리 메서드 63개를 같은 조건으로 재고, 인덱스마다 이득(블록·p95)과 비용(크기·쓰기당 WAL·HOT 업데이트)을 비교해 필요한 것만 남겼습니다.

| 쿼리 | 전 | 후 |
|---|---|---|
| 보관함 전체 탭 (UNION ALL + 부분 인덱스) | 18,473블록 / p50 23.8ms | 97블록 / 0.03ms |
| 홈 인기 보드 | 8,242블록 / p95 39.7ms | 13블록 / 0.01ms |

보관함은 같은 v2 데이터에서 재작성 전후를 나란히 잰 값입니다. 인기 보드의 '전'은 인덱스를 넣던 2026-10 측정(보드 25만) 값이고, '후'는 v2에서도 같은 블록 수입니다.
사용자가 기다리는 쿼리는 모두 1ms 아래이고, 보관함 쿼리는 데이터가 늘수록 차이가 벌어집니다(보드 25만에서 1/85 → 50만에서 1/190).

**보드 signature 조회 — 인덱스 대신 쿼리를 고쳤습니다.** 같은 구성의 보드를 찾는 조회는 보드 종류마다 있는 부분 UNIQUE 인덱스(`uk_board_ai_signature` 등)가 맡습니다. Hibernate는 JPQL의 enum 값을 `board_type = ?`로 보내는데, 값을 모르는 범용 실행 계획(generic plan)은 이 부분 인덱스를 쓰지 못해 전체를 훑습니다(강제하면 8,163블록 / 11.4ms). 이를 막으려 두었던 `(board_type, content_signature, user_id)` 인덱스를 지우고, 네이티브 쿼리에 board_type을 상수로 박아 어떤 계획에서도 UNIQUE 인덱스를 타게 했습니다.

- 조회 속도는 거의 같습니다. 기본 설정(`plan_cache_mode = auto`)에서는 PostgreSQL이 비용을 비교해 커스텀 계획을 고르므로 전후 모두 4블록이고 차이는 0.01ms입니다. 범용 계획으로 넘어간 것은 보드 약 355개 이하(전체 스캔도 7페이지)일 때뿐이었습니다.
- 얻은 것은 쓰기 비용입니다. 지운 인덱스는 UNIQUE 인덱스와 같은 signature를 한 벌 더 담아 24MB였고, 좋아요 수 변경 1건마다 WAL +32%, 보드 생성마다 +24%였습니다.
- 대가로 보드 종류마다 네이티브 메서드가 따로 있고, 쿼리의 상수가 인덱스 조건과 글자 그대로 맞아야 합니다.

### API 부하 테스트 (AWS)

운영과 같은 AWS 환경(backend 2 vCPU 작업 1개, RDS db.t4g.small, Qdrant m7g.large)에 같은 리전 EC2의 k6로 **화면 단위** 부하를 걸어 상한까지 쟀습니다. 화면 비율은 홈 68 / 검색 21 / 보관함 10 / 가입 1%, 목표는 p95 500ms(검색 1.5초)·실패 1% 미만입니다.

| | Redis 캐시 있음 | 캐시 없음 |
|---|---|---|
| 목표를 지킨 최대 | 화면 40/s (API 약 170/s) | 화면 20/s |
| 첫 포화 | 화면 100/s | 화면 60~80/s |
| 먼저 찬 자원 | backend CPU + 커넥션 풀 | Qdrant CPU |

| 조치 | 효과 (AWS 측정) |
|---|---|
| 재계산을 대기열로(사용자별로 합쳐 동시 2개) + 트랜잭션을 저장 단계에만 | 목표를 지킨 좋아요→취소 초당 1쌍 → 180쌍, 같은 초당 5쌍에서 커넥션 풀 대기 최대 158 → 0 |
| 추천 결과 Redis 캐시(취향 벡터 버전으로 무효화) | 홈 추천 단독 처리 상한 약 37/s → 80/s, 화면 상한 약 2배 |
| open-in-view 끄기, Qdrant 호출을 트랜잭션 밖으로 | Qdrant가 포화돼도 다른 API p95 17ms 유지 (로컬 측정) |
| 보드 제목 검색 4번 → 묶음 검색 1번 | Qdrant CPU 약 1/3 감소 (로컬 측정) |

API별 단독 상한(인기 보드 약 840/s, 콘텐츠 상세 약 1,440/s 등)과 병목 분석은 측정 기록에 있습니다. 화면 상한을 정하는 것은 검색 보드(AI 서버)와 홈 추천(Qdrant)입니다.
측정 방법·도구: [`load-test/README.md`](load-test/README.md)(로컬), [`load-test/aws/README-api.md`](load-test/aws/README-api.md)(AWS), [`docs/load-test-and-metrics.md`](docs/load-test-and-metrics.md).

## 운영

- **배포**: main에 merge하면 GitHub Actions가 테스트 → ARM64 이미지 빌드 → ECS 롤링 배포를 한다. 새 작업이 헬스 체크를 통과한 뒤 옛 작업을 내리고(무중단), 실패하면 이전 버전으로 되돌린다. AWS 키는 저장하지 않고 OIDC 역할로 받는다. → [`docs/deployment.md`](docs/deployment.md)
- **DB 백업**: RDS 자동 백업(매일 스냅샷 + 7일 시점 복구)에 더해 매일 03:30 `pg_dump` 전체를 S3에 30일 보관한다. 실패하면 메일로 알린다. → [`docs/db-backup.md`](docs/db-backup.md)
- **인프라**: Terraform으로 관리한다. 비용 때문에 쓰지 않을 때는 환경을 끈다(`infra/terraform/scripts/env-down.sh`). → [`infra/terraform/README.md`](infra/terraform/README.md)

## 로컬 실행

### 준비물

1. **환경변수 파일** — 저장소 루트에 `crates_server.env`를 만든다.

   ```
   POSTGRESQL_USERNAME=
   POSTGRESQL_PASSWORD=
   JWT_SECRET_KEY=
   SEARCH_LIKE_TOKEN_SECRET=
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
   | `book_query_vectors.csv`, `movie_query_vectors.csv`, `music_query_vectors.csv` | 보드 제목 후보(query)와 그 벡터 |
   | `popular_top200_ids.csv` | 가입 직후 고를 취향 콘텐츠 후보(종류별 인기순 200개) |

3. **AI 서버** — 검색 보드에 필요하다. 주소는 `application.yaml`의 `ai.server.base-url`(환경 변수 `AI_SERVER_BASE_URL`).

### 띄우기

```bash
docker compose --env-file crates_server.env up -d --build
```

처음 띄우면 Flyway가 스키마를 만들고 콘텐츠를 시딩한 뒤(V1~V9), 콘텐츠 벡터와 query 벡터를 CSV에서 Qdrant에 적재한다. 4~5분 걸린다.

- Qdrant는 볼륨(`qdrant_data`)에 저장한다. backend가 뜰 때 RDB의 콘텐츠 수·모델 버전과 맞는지 확인하고, 다르면 컬렉션을 지우고 다시 적재한다.
- 처음부터 다시 시작하려면 `docker compose down -v`로 볼륨까지 지운다.
- 테스트: `./gradlew test`. 벤치마크(`RepositoryBenchmarkTest`)는 시딩한 DB가 있어야 하며 CI에서는 빠진다.

| 서비스 | 포트 |
|---|---|
| backend | 8080 |
| PostgreSQL | 5432 |
| Qdrant | 6333(REST), 6334(gRPC) |
| Redis | 6379 |
| MinIO | 9000(S3 API), 9001(콘솔) |

### 부하 측정 구성 (로컬)

```bash
docker compose --env-file crates_server.env -f docker-compose.yml -f docker-compose.loadtest.yml up -d --build
SEED_VERSION=2 SKIP_RECALC=1 ./load-test/prepare.sh 4.625 1 0.6 1.0 1.5417   # 6개월 운영 데이터 시딩(v2)
./load-test/run-sweep.sh mixed 25 50 75 100 150           # 도착률을 단계별로 올려 측정
```

자원 상한(`BACKEND_CPUS`, `PG_CPUS`, `QDRANT_CPUS`)과 시나리오 설명은 [`load-test/README.md`](load-test/README.md)에 있다. 측정이 끝나면 `load-test/cleanup.sql`로 테스트 계정(`loadtest*`)과 그 데이터를 지운다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/oauth2/authorization/{google\|kakao\|naver}` | 소셜 로그인 시작(로그인 중 요청 정보는 암호화한 쿠키에 둔다) |
| POST | `/api/auth/oauth-token` | 소셜 로그인 후 임시 토큰을 JWT로 교환 |
| POST | `/api/auth/profile` | 가입 직후 프로필 입력 |
| POST | `/api/auth/refresh`, `/api/auth/logout` | 토큰 갱신, 로그아웃 |
| POST | `/api/auth/login` | 아이디·비밀번호 로그인(부하 테스트 계정 전용, 가입은 소셜만) |
| GET | `/api/users/me/onboarding` | 취향 콘텐츠를 골랐는지 |
| GET | `/api/contents/onboarding?type=` | 가입 직후 고를 취향 콘텐츠 후보(종류별) |
| POST | `/api/users/me/initial-contents` | 고른 콘텐츠로 첫 취향 벡터 만들기 |
| GET | `/api/boards/recommendation/user` | 홈 추천 보드 4개 |
| GET | `/api/boards/liked?n=` | 인기 보드 |
| GET | `/api/boards/mine?filter=&page=&size=` | 보관함(ALL / LIKED / CREATED) |
| GET | `/api/boards/list?boardId=` | 보드 상세 |
| GET | `/api/search/board?keyword=` | 검색 보드 |
| POST | `/api/boards/likes` | 홈 추천 보드 좋아요(토큰으로 저장 + 좋아요) |
| POST | `/api/search/board/likes` | 검색 보드 좋아요(토큰으로 저장 + 좋아요) |
| POST·DELETE | `/api/boards/{boardId}/likes` | 저장된 보드 좋아요·취소 |
| POST | `/api/boards` | 보드를 고쳐 내 보드로 새로 저장 |
| PUT | `/api/boards/{boardId}` | 보드 수정(남의 보드면 복제본) |
| DELETE | `/api/boards/{boardId}` | 내 보드 삭제 |
| POST | `/api/logs/board-capture` | 보드 사진 저장 기록(행동 로그) |
| GET | `/api/contents/search?keyword=` | 콘텐츠 제목 검색(3글자 이상) |
| GET | `/api/contents/{contentId}`, `/api/contents/{dtype}/{contentId}` | 콘텐츠 요약, 상세 |

## 디렉터리

```
src/main/java/com/crates/crates
├── controller · service · repository · entity · DTO
├── qdrant        Qdrant 호출(검색·묶음 검색·조회·적재)
├── ai            AI 서버 임베딩 클라이언트(요청 묶기·동시 호출 상한)
├── initializer   기동 시 콘텐츠 벡터·query 벡터 적재
├── seed          Flyway Java 마이그레이션(콘텐츠·이미지·취향 후보 시딩)
├── scheduler     취향 벡터 백필, 만료 토큰 정리, 행동 로그 내보내기
├── job           일회성 작업(콘텐츠 이미지 채우기)
└── jwt · oauth · user · config · Global
src/main/resources/db/migration   기능별 스키마(V1 콘텐츠, V2 사용자, V3 보드, V4 행동 로그, V8 제목 검색 인덱스)
infra/terraform/                  AWS 인프라(prod), 켜기·끄기·백업·작업 스크립트
.github/workflows/                CI/CD(테스트, 이미지, ECS 배포)
load-test/                        시더, k6 시나리오, 로컬·AWS 측정 실행기
docs/                             보드 스키마 설계, 부하 측정 기록, 배포, DB 백업
```
