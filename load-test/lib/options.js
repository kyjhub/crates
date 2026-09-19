// 모든 시나리오가 공유하는 k6 옵션.
//
// 워밍업은 이 파일이 아니라 실행 스크립트(run-measure.sh)가 담당한다. k6를 한 번 버리고
// 한 번 측정하는 방식이다. 한 프로세스 안에서 두 구간을 나누는 방법도 있지만,
// shared-iterations는 끝나는 시각을 알 수 없어 measure 구간의 startTime을 정할 수 없다.
// (추정치를 넣으면 두 구간이 겹쳐 측정 초반이 다시 차가워진다.)
//
// 워밍업이 왜 필요한가 — 같은 조건 7회 반복 실측:
//
//   1회 104.0 TPS / 2회 154.9 / 3회 173.2 / 4회 197.2 / 5회 197.3 / 6회 206.7 / 7회 209.7
//
// 첫 회가 정상 상태보다 50% 느리다. 4회차부터 수렴하고 그 뒤 변동은 ±6%다.
// 앱을 재시작하면 다시 식는다(재시작 직후 113.3 TPS). JVM JIT 워밍업이라 DB를 비워도 안 풀린다.
//
// 이걸 모르고 1회 측정으로 비교하면 워밍업 차이를 개선 효과로 잘못 읽는다. 실제로 그랬다.

/**
 * 처리율은 http_reqs가 아니라 iterations로 본다.
 *
 * http_reqs에는 setup()의 회원가입·로그인이 섞인다. 실측: iterations 200건인 실행에서
 * http_reqs는 220건이고 rate가 134.6 vs 122.4로 10% 부풀려진다.
 * setup에 걸린 시간도 분모에 들어간다.
 */
export const RATE_METRIC = 'iterations';

/**
 * @param vus         동시 사용자 수
 * @param iterations  측정할 반복 수
 * @param p95         p95 상한(ms). 넘으면 threshold 실패로 드러난다
 */
export function standard({ vus, iterations, p95 = 2000 }) {
  return {
    scenarios: {
      main: {
        executor: 'shared-iterations',   // 총량 고정. 실행 간 비교가 성립하려면 일감이 같아야 한다.
        vus,
        iterations,
        maxDuration: '10m',
        gracefulStop: '10s',
      },
    },
    thresholds: {
      // 실패가 섞이면 처리율 비교가 무의미해진다. 먼저 막는다.
      checks: ['rate==1.0'],
      http_req_duration: [`p(95)<${p95}`],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  };
}
