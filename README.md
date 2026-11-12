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
- 테스트: `./gradlew test`.

| 서비스 | 포트 |
|---|---|
| backend | 8080 |
| PostgreSQL | 5432 |
| Qdrant | 6333(REST), 6334(gRPC) |
| Redis | 6379 |
| MinIO | 9000(S3 API), 9001(콘솔) |

## 주요 기능

- **소셜 로그인과 취향 설정** — Google, Kakao, Naver로 로그인하고 관심 콘텐츠를 선택합니다.
- **맞춤 보드 추천** — 선택한 콘텐츠와 좋아요를 바탕으로 홈에서 취향에 맞는 보드를 추천받습니다.
- **보드·콘텐츠 검색** — 키워드로 보드와 콘텐츠를 찾고 상세 정보를 확인합니다.
- **보드 편집과 보관함** — 콘텐츠와 배치를 편집해 내 보드로 저장하고, 만든 보드와 좋아요한 보드를 모아 봅니다.

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
docs/                             보드 스키마 설계, 배포, DB 백업
```

## 라이선스

이 저장소의 코드에 적용되는 저작권 고지는 [LICENSE](LICENSE)를 참조하세요.
