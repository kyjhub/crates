// 측정 대상 요청만 담는 지표. setup에서 준비용으로 보내는 요청은 여기에 넣지 않는다.
//
// http_req_duration을 쓰지 않는 이유: setup의 준비 요청까지 분포에 섞여 p95가 측정 대상과 무관하게 움직인다.

import { Rate, Trend } from 'k6/metrics';

export const apiDuration = new Trend('api_duration', true);
export const apiFailed = new Rate('api_failed');

/**
 * 측정 대상 응답 하나를 기록한다. 2xx가 아니면 실패로 센다.
 *
 * tags는 혼합 시나리오가 엔드포인트별 p95를 따로 보려고 넘긴다(예: { name: 'recommend', ai: 'no' }).
 * 단일 엔드포인트 시나리오는 넘기지 않는다.
 */
export function record(res, tags) {
  apiDuration.add(res.timings.duration, tags);
  apiFailed.add(res.status < 200 || res.status >= 300, tags);
  return res;
}
