# 배포 (GitHub Actions → ECS / S3)

main에 merge하면 사람이 손대지 않고 운영에 나간다. 배포 중에도 요청이 끊기지 않게(무중단) 한다.

- 작성: 2026-11-09
- 저장소: 백엔드 `kyjhub/crates`, 프론트 `kyjhub/crates_frontend`(2026-11-09 팀 조직 VibeCrates에서 옮김. 팀 저장소는 더 쓰지 않는다)
- 워크플로: 백엔드 `.github/workflows/backend.yml`, 프론트 `kyjhub/crates_frontend`의 `.github/workflows/frontend.yml`
- AWS 쪽(역할·롤링 설정): `infra/terraform/prod/github_oidc.tf`, `ecs.tf`, `alb.tf`

---

## 1. 흐름

### 백엔드

```
PR        → test (빈 PostgreSQL 15 서비스 + 가짜 비밀값으로 ./gradlew test)
main push → test → image (ARM 실행기에서 docker build, ECR에 커밋 SHA 7자리 태그)
               → deploy (작업 정의의 이미지만 바꾼 새 리비전 → 서비스 갱신 → 롤아웃 완료까지 대기 → SSM에 태그 기록)
```

- `docs/`, `load-test/`, `infra/`, `*.md`만 바뀐 push는 배포하지 않는다. Terraform은 사람이 plan을 보고 적용한다.
- 같은 커밋을 다시 돌리면(Actions 화면의 Re-run, 또는 workflow_dispatch) 이미 있는 이미지를 그대로 쓴다. ECR 태그는 덮어쓸 수 없다.
- 환경을 꺼 둔 동안(`active = false`)은 ECS 서비스가 없다. 이때는 새 리비전 등록과 태그 기록만 하고 끝난다. 다음에 `env-up.sh`로 켜면 그 태그로 뜬다.
- 벤치마크 테스트(`@Tag("benchmark")`)는 시딩한 개발 DB가 있어야 해서 CI에서 빠진다.

### 프론트

```
PR        → lint + build
main push → lint + build → assets/를 1년 캐시로 올림 → index.html 등은 no-cache로 올림 → CloudFront /* 무효화
```

---

## 2. 무중단이 되는 이유

| 단계 | 하는 것 | 설정 |
| --- | --- | --- |
| 새 작업 기동 | 옛 작업을 그대로 두고 새 작업을 하나 더 띄운다 | `deployment_minimum_healthy_percent = 100`, `maximum_percent = 200` |
| 트래픽 받기 | ALB가 `/actuator/health/readiness`를 15초마다 2번 통과해야 새 작업에 요청을 보낸다. 기동(Flyway 검증 포함) 동안은 헬스 체크 실패로 내리지 않는다 | `alb.tf` health_check, `health_check_grace_period_seconds = 120` |
| 옛 작업 내리기 | ALB에서 먼저 빼고 30초 동안 처리 중인 요청을 마친다. 그다음 SIGTERM → Spring이 진행 중인 요청을 끝내고 종료(최대 40초) | `deregistration_delay = 30`, `server.shutdown: graceful`, `stopTimeout = 40` |
| 실패 | 새 작업이 계속 헬스 체크에 실패하면 회로 차단기가 이전 리비전으로 되돌린다. 워크플로는 실패로 끝나고, SSM 태그는 바뀌지 않는다 | `deployment_circuit_breaker { rollback = true }` |
| 프론트 | 새 청크를 먼저 올리고 index.html을 마지막에 바꾼다. 옛 청크를 지우지 않아, 배포 직전에 연 페이지도 깨지지 않는다 | `frontend.yml` |

배포 한 번에 1~2분 동안 옛 작업과 새 작업이 함께 요청을 받는다. 그래서 아래 두 가지를 지켜야 한다.

### 2-1. DB 마이그레이션은 옛 코드와 함께 돌 수 있어야 한다

새 작업이 뜨면서 Flyway가 마이그레이션을 적용한다. 그 순간부터 옛 작업도 바뀐 스키마로 요청을 처리한다.

| 하고 싶은 것 | 한 번에 하면 | 이렇게 나눈다 |
| --- | --- | --- |
| 컬럼 추가 | NOT NULL·기본값 없는 컬럼이면 옛 코드의 INSERT가 실패한다 | NULL 허용 또는 기본값을 두고 추가 → (필요하면) 다음 배포에서 NOT NULL |
| 컬럼 삭제 | 옛 코드가 그 컬럼을 읽다 실패한다 | ① 코드에서 안 쓰게 배포 → ② 다음 배포에서 컬럼 삭제 |
| 컬럼·테이블 이름 변경 | 옛 코드가 옛 이름을 찾다 실패한다 | 새 컬럼 추가 + 양쪽에 쓰기 → 데이터 옮기기 → 새 이름만 읽기 → 옛 컬럼 삭제 |
| 큰 테이블 인덱스 | 테이블 잠금이 길어 그동안 쓰기가 멈춘다 | `CREATE INDEX CONCURRENTLY`(트랜잭션 밖에서 실행되는 별도 마이그레이션) |

위험한 마이그레이션 전에는 백업을 뜬다(`infra/terraform/scripts/run-db-backup.sh`, `docs/db-backup.md`).

### 2-2. 서버 메모리에 사용자 상태를 두지 않는다

요청마다 어느 작업이 받을지 모른다. 로그인 상태(JWT), 캐시(Redis)처럼 작업 밖에 둔다.
OAuth 로그인 중의 요청 정보(state 등)도 서버 세션이 아니라 쿠키에 담는다(`HttpCookieOAuth2AuthorizationRequestRepository`).

---

## 3. 처음 한 번 설정

### GitHub 저장소 변수 (Settings → Secrets and variables → Actions → Variables)

비밀이 아니라 이름이다. 값은 `infra/terraform/prod`에서 `terraform output -raw <이름>`.

| 저장소 | 변수 | output |
| --- | --- | --- |
| kyjhub/crates | `AWS_BACKEND_DEPLOY_ROLE_ARN` | `github_backend_deploy_role_arn` |
| kyjhub/crates_frontend | `AWS_FRONTEND_DEPLOY_ROLE_ARN` | `github_frontend_deploy_role_arn` |
| kyjhub/crates_frontend | `FRONTEND_BUCKET` | `frontend_bucket` |
| kyjhub/crates_frontend | `CLOUDFRONT_DISTRIBUTION_ID` | `cloudfront_distribution_id` |

### main 보호 규칙 (Settings → Rules → Rulesets, 두 저장소 모두)

- main에 바로 push 금지, PR로만 merge
- 필수 상태 검사: 백엔드 `test`, 프론트 `build`
- 배포는 main에 merge되면 바로 나간다 — 수동 승인 단계는 두지 않았다(혼자 운영하는 동안). 승인을 넣으려면 GitHub Environment를 쓰는데,
  그러면 OIDC 토큰의 sub가 `repo:…:environment:<이름>`으로 바뀌므로 `github_oidc.tf`의 신뢰 조건도 같이 바꾼다.

---

## 4. 되돌리기

배포 자체가 실패하면(헬스 체크 실패) ECS가 자동으로 되돌린다. 배포는 성공했는데 기능에 문제가 있으면:

1. **코드로 되돌리기(권장)**: 문제 커밋을 `git revert`해 PR → merge. 같은 파이프라인으로 나간다.
2. **급할 때 이전 이미지로**: ECR에 최근 20개 이미지가 남아 있다.
   ```bash
   aws ecs describe-task-definition --task-definition crates-prod-backend   # 현재
   aws ecs list-task-definitions --family-prefix crates-prod-backend --sort DESC --max-items 5
   aws ecs update-service --cluster crates-prod --service backend --task-definition crates-prod-backend:<이전 리비전>
   aws ssm put-parameter --name /crates/prod/backend-image-tag --value <이전 태그> --overwrite   # 환경을 껐다 켜도 그 이미지로
   ```
   DB 마이그레이션이 들어간 배포라면 이전 코드가 새 스키마와 맞는지(2-1) 먼저 본다. Flyway는 되돌리지 않는다.

---

## 5. 확인할 곳

- 진행: GitHub Actions 실행 화면. 백엔드 `deploy` 작업이 15초마다 롤아웃 상태를 찍는다.
- ECS: `aws ecs describe-services --cluster crates-prod --services backend --query 'services[0].events[:10]'`
- 로그: CloudWatch `/ecs/crates-prod-backend`
- 지금 나가 있는 태그: `aws ssm get-parameter --name /crates/prod/backend-image-tag --query Parameter.Value --output text`
