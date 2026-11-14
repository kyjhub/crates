"""RDS 마스터 비밀번호가 교체되면 backend 서비스를 새로 배포한다(rotation_redeploy.tf).

ECS는 작업을 시작할 때 Secrets Manager에서 비밀번호를 한 번 읽어 환경 변수로 넣고, 실행 중에는 다시 읽지 않는다.
그래서 교체 뒤 커넥션 풀이 커넥션을 새로 만들 때 인증에 실패한다(2026-11-13 장애). 새 작업은 바뀐 비밀번호로 뜬다.
배포는 롤링이라 새 작업이 정상이 된 뒤 옛 작업을 내린다.
"""
import os

import boto3

ecs = boto3.client("ecs")


def handler(event, context):
    secret = event.get("detail", {}).get("additionalEventData", {}).get("SecretId")
    response = ecs.update_service(cluster=os.environ["CLUSTER"], service=os.environ["SERVICE"], forceNewDeployment=True)
    deployment = response["service"]["deployments"][0]["id"]
    print(f"secret rotated: {secret} -> new deployment {deployment}")
    return {"deployment": deployment}
