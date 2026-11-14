# ── DB 비밀번호 교체 → backend 재배포 ───────────────────────────────
# RDS가 마스터 비밀번호를 7일마다 바꾸는데(manage_master_user_password), ECS 작업은 시작할 때 읽은 비밀번호를
# 계속 쓴다. 교체 뒤 커넥션 풀이 새 커넥션을 열면 인증에 실패해 로그인부터 막혔다(2026-11-13).
# Secrets Manager의 교체 성공 이벤트를 받아 backend를 새로 배포하고, 같은 내용을 알림 메일로도 보낸다.
# 서비스가 있을 때만 만든다(local.serving).

data "archive_file" "redeploy_backend" {
  type        = "zip"
  source_file = "${path.module}/templates/redeploy_backend.py"
  output_path = "${path.module}/.build/redeploy_backend.zip"
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "redeploy_backend" {
  count = local.serving ? 1 : 0

  name               = "${local.name}-redeploy-backend"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

# backend 서비스 하나만 다시 배포할 수 있다. 작업 정의·이미지는 바꾸지 못한다.
resource "aws_iam_role_policy" "redeploy_backend" {
  count = local.serving ? 1 : 0

  name = "redeploy-backend"
  role = aws_iam_role.redeploy_backend[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecs:UpdateService"]
        Resource = aws_ecs_service.backend[0].id
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.redeploy_backend.arn}:*"
      }
    ]
  })
}

resource "aws_cloudwatch_log_group" "redeploy_backend" {
  name              = "/aws/lambda/${local.name}-redeploy-backend"
  retention_in_days = 30
}

resource "aws_lambda_function" "redeploy_backend" {
  count = local.serving ? 1 : 0

  function_name    = "${local.name}-redeploy-backend"
  role             = aws_iam_role.redeploy_backend[0].arn
  runtime          = "python3.13"
  architectures    = ["arm64"]
  handler          = "redeploy_backend.handler"
  filename         = data.archive_file.redeploy_backend.output_path
  source_code_hash = data.archive_file.redeploy_backend.output_base64sha256
  timeout          = 30

  environment {
    variables = {
      CLUSTER = aws_ecs_cluster.main.name
      SERVICE = aws_ecs_service.backend[0].name
    }
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.redeploy_backend.name
  }
}

# 교체가 끝났을 때(RotationSucceeded) 한 번. 교체 도중(RotationStarted)에는 새 비밀번호가 아직 AWSCURRENT가 아니다.
resource "aws_cloudwatch_event_rule" "db_secret_rotated" {
  count = local.serving ? 1 : 0

  name        = "${local.name}-db-secret-rotated"
  description = "RDS master password rotation succeeded"
  event_pattern = jsonencode({
    source      = ["aws.secretsmanager"]
    detail-type = ["AWS Service Event via CloudTrail"]
    detail = {
      eventSource         = ["secretsmanager.amazonaws.com"]
      eventName           = ["RotationSucceeded"]
      additionalEventData = { SecretId = [aws_db_instance.main.master_user_secret[0].secret_arn] }
    }
  })
}

resource "aws_cloudwatch_event_target" "db_secret_rotated_redeploy" {
  count = local.serving ? 1 : 0

  rule = aws_cloudwatch_event_rule.db_secret_rotated[0].name
  arn  = aws_lambda_function.redeploy_backend[0].arn
}

resource "aws_cloudwatch_event_target" "db_secret_rotated_notify" {
  count = local.serving ? 1 : 0

  rule = aws_cloudwatch_event_rule.db_secret_rotated[0].name
  arn  = aws_sns_topic.alerts.arn
  input_transformer {
    input_paths    = { time = "$.time" }
    input_template = "\"RDS 마스터 비밀번호가 교체되어(<time>) backend를 새로 배포합니다. 결과는 /aws/lambda/${local.name}-redeploy-backend 로그와 ECS 서비스 이벤트에서 확인하세요.\""
  }
}

resource "aws_lambda_permission" "db_secret_rotated" {
  count = local.serving ? 1 : 0

  statement_id  = "AllowEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.redeploy_backend[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.db_secret_rotated[0].arn
}

# 재배포 Lambda가 실패하면 비밀번호가 바뀐 채 옛 작업이 남는다. 바로 알린다.
resource "aws_cloudwatch_metric_alarm" "redeploy_backend_failed" {
  count = local.serving ? 1 : 0

  alarm_name          = "${local.name}-redeploy-backend-failed"
  alarm_description   = "DB 비밀번호 교체 뒤 backend 재배포 Lambda가 실패했습니다. ECS backend 서비스에서 Force new deployment를 직접 실행하세요."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.redeploy_backend[0].function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}
