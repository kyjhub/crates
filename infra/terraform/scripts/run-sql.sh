#!/usr/bin/env bash
# 운영 PostgreSQL에 SQL 파일 하나를 실행한다. 매일 백업 작업(db_backup.tf)과 같은 작업 정의를 명령만 바꿔 띄운다 —
# 같은 사설 서브넷·보안 그룹·RDS 비밀이라 노트북에서 포트 포워딩을 열 필요가 없다.
#   infra/terraform/scripts/run-sql.sh load-test/cleanup.sql
#
# psql -v ON_ERROR_STOP=1로 돌려, 문장 하나라도 실패하면 거기서 멈추고 0이 아닌 코드로 끝난다.
# 파일 안의 BEGIN … COMMIT은 그대로 한 트랜잭션이고, VACUUM처럼 트랜잭션 밖이어야 하는 문장도 쓸 수 있다.
# 지우거나 고치는 SQL이면 먼저 run-db-backup.sh로 백업을 뜬다.
# 전제: 환경이 켜져 있어야 한다(RDS available, NAT 있음 — 작업이 패키지를 받는다).
set -euo pipefail

SQL_FILE=${1:?"실행할 SQL 파일을 넘긴다(예: load-test/cleanup.sql)"}
[ -f "$SQL_FILE" ] || { echo "파일이 없습니다: $SQL_FILE" >&2; exit 1; }

# 작업 정의 덮어쓰기(overrides)는 8KB까지라 압축해 넘긴다. cleanup.sql 4.7KB → 약 2.5KB.
SQL_B64=$(gzip -9c "$SQL_FILE" | base64 | tr -d '\n')
[ ${#SQL_B64} -lt 7000 ] || { echo "SQL이 너무 크다(압축 후 ${#SQL_B64}자). 나눠서 돌린다." >&2; exit 1; }

cd "$(dirname "$0")/../prod"
CLUSTER=$(terraform output -raw ecs_cluster)
FAMILY=$(terraform output -raw db_backup_task_family)
SG=$(terraform output -raw db_backup_security_group_id)
SUBNETS=$(terraform output -json app_subnet_ids | python3 -c "import json,sys;print(','.join(json.load(sys.stdin)))")
cd - >/dev/null

OVERRIDES=$(python3 - "$SQL_B64" <<'PY'
import json, sys
script = ("set -euo pipefail; dnf install -y -q postgresql15 gzip >/dev/null; "
          "echo \"$SQL_B64\" | base64 -d | gunzip > /tmp/run.sql; "
          "echo \"[run-sql] $(wc -c < /tmp/run.sql) bytes, sha256 $(sha256sum /tmp/run.sql | cut -c1-16)\"; "
          "psql -v ON_ERROR_STOP=1 -X -f /tmp/run.sql; echo '[run-sql] 끝'")
print(json.dumps({"containerOverrides": [{
    "name": "db-backup",
    "command": ["bash", "-c", script],
    "environment": [{"name": "SQL_B64", "value": sys.argv[1]}],
}]}))
PY
)

TASK_ARN=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$FAMILY" --launch-type FARGATE \
  --started-by manual-sql \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=DISABLED}" \
  --overrides "$OVERRIDES" --query 'tasks[0].taskArn' --output text)
TASK_ID=${TASK_ARN##*/}
echo "작업 시작: $TASK_ID ($SQL_FILE, sha256 $(shasum -a 256 "$SQL_FILE" | cut -c1-16))"

# VACUUM FULL이 있으면 10분(tasks-stopped 대기 상한)을 넘길 수 있어 직접 기다린다.
while [ "$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].lastStatus' --output text)" != "STOPPED" ]; do
  sleep 15
done
EXIT_CODE=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].containers[0].exitCode' --output text)
REASON=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].stoppedReason' --output text)
aws logs get-log-events --log-group-name "/ecs/$FAMILY" --log-stream-name "db-backup/db-backup/$TASK_ID" \
  --query 'events[].message' --output text 2>/dev/null | tr '\t' '\n' || true
echo "끝: 종료 코드 $EXIT_CODE ($REASON)"
[ "$EXIT_CODE" = "0" ]
