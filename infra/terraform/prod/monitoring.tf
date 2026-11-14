# ── 서비스 장애 알림 ──────────────────────────────────────────────
# backend가 죽거나 5xx가 늘면 DB 백업 알림과 같은 메일(aws_sns_topic.alerts)로 보낸다. 복구되면 OK 메일도 보낸다.
# ALB가 있을 때만 만든다(local.serving). 환경을 끄면 ALB와 함께 지워진다.

locals {
  alb_dimensions = local.serving ? {
    LoadBalancer = aws_lb.main[0].arn_suffix
    TargetGroup  = aws_lb_target_group.backend[0].arn_suffix
  } : {}
}

# 서버가 죽었다: 정상 응답하는 backend 작업이 2분 연속 0개.
# 롤링 배포 중에는 새 작업이 정상이 된 뒤 옛 작업을 내리므로 0이 되지 않는다.
# 표본이 없으면(대상 그룹에 작업이 하나도 없으면) 죽은 것으로 본다.
resource "aws_cloudwatch_metric_alarm" "backend_down" {
  count = local.serving ? 1 : 0

  alarm_name          = "${local.name}-backend-down"
  alarm_description   = "정상 backend 작업이 0개입니다. ECS 서비스 이벤트와 /ecs/${local.name}-backend 로그를 확인하세요."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = local.alb_dimensions
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

# backend가 낸 5xx(애플리케이션 오류): 5분에 10건 이상.
# 한두 건의 일시 오류(AI 서버 시간 초과 등)로 울리지 않게 건수 기준을 둔다.
resource "aws_cloudwatch_metric_alarm" "backend_5xx" {
  count = local.serving ? 1 : 0

  alarm_name          = "${local.name}-backend-5xx"
  alarm_description   = "backend 5xx 응답이 5분에 10건 이상입니다. /ecs/${local.name}-backend 로그의 ERROR를 확인하세요."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = local.alb_dimensions
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching" # 요청이 없으면 표본이 없다
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

# ALB가 직접 낸 5xx(502·503·504): backend에 닿지 못했거나 응답이 끊긴 경우. 5분에 10건 이상.
resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  count = local.serving ? 1 : 0

  alarm_name          = "${local.name}-alb-5xx"
  alarm_description   = "ALB 5xx(502·503·504)가 5분에 10건 이상입니다. backend 작업 상태와 대상 그룹 헬스 체크를 확인하세요."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  dimensions          = { LoadBalancer = local.serving ? aws_lb.main[0].arn_suffix : "" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

# DB 인증 실패·커넥션 대기 시간 초과: 5분에 3건 이상.
# 2026-11-13 비밀번호 교체 장애는 로그인 실패가 302(/login?error)로 나가고 헬스 체크도 DB를 보지 않아
# 위 경보들이 잡지 못했다. backend 로그에서 직접 센다.
resource "aws_cloudwatch_log_metric_filter" "db_connection_errors" {
  count = local.serving ? 1 : 0

  name           = "${local.name}-db-connection-errors"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "?\"password authentication failed\" ?\"Connection is not available\""

  metric_transformation {
    name      = "DbConnectionErrors"
    namespace = "Crates/Backend"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "db_connection_errors" {
  count = local.serving ? 1 : 0

  alarm_name          = "${local.name}-db-connection-errors"
  alarm_description   = "backend가 DB에 연결하지 못합니다(인증 실패 또는 커넥션 대기 시간 초과). 비밀번호 교체 직후라면 ECS backend 서비스를 새로 배포하세요. 로그: /ecs/${local.name}-backend"
  namespace           = "Crates/Backend"
  metric_name         = "DbConnectionErrors"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 3
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  depends_on = [aws_cloudwatch_log_metric_filter.db_connection_errors]
}
