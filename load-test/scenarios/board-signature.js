// V7 idx_board_signature_lookup 검증.
//
// 무엇을 보나
//   likeNewBoard는 호출할 때마다 board 행을 하나 만들고, 그 과정에서
//   findActiveByTypeAndSignature로 같은 구성의 보드가 있는지 찾는다.
//   인덱스가 없으면 이 조회가 board 전체를 훑으므로 테이블이 커질수록 느려진다.
//
// 어떻게 읽나
//   이 스크립트를 여러 번 연달아 돌린다. 회차마다 board 행이 늘어나므로,
//   처리량이 회차에 따라 떨어지면 인덱스가 안 듣는 것이고 평평하면 듣는 것이다.
//   run-signature-curve.sh 가 그 반복과 board 행 수 출력을 담당한다.
//
// 기준선 (인덱스 없던 2026-09-14, 같은 조건 100건/VU 20)
//   board   620행 -> 107 TPS
//   board 3,128행 ->  40 TPS      <- 이 하락이 사라져야 한다

import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { ensureUser, likeNewBoard, PREFIX } from '../lib/api.js';

const USERS = Number(__ENV.USERS || 20);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);

export const options = {
  scenarios: {
    like_new_board: {
      executor: 'shared-iterations',   // VU들이 정해진 횟수를 나눠 가진다. 회차 간 비교를 위해 총량 고정.
      vus: USERS,
      iterations: ITERATIONS,
      maxDuration: '5m',
    },
  },
  thresholds: {
    // 실패가 섞이면 처리량 비교가 무의미해진다. 먼저 막는다.
    checks: ['rate==1.0'],
    // 기준선의 붕괴 지점(p95 4.7초)보다 한참 아래에 둔다. 넘으면 인덱스가 안 듣는 것.
    'http_req_duration{name:POST /api/boards/likes}': ['p(95)<1000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const failures = new Counter('like_failures');

export function setup() {
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    tokens.push(ensureUser(`sig${i}`));
  }
  return { tokens };
}

export default function (data) {
  // VU 번호로 계정을 고정한다. 계정마다 좋아요 이력이 다르면 재계산 비용이 달라져
  // 회차 간 비교가 흔들린다.
  const token = data.tokens[(__VU - 1) % data.tokens.length];

  const res = likeNewBoard(token, `${PREFIX}-sig-${__VU}-${__ITER}`);
  const ok = check(res, { '좋아요 200': (r) => r.status === 200 });
  if (!ok) {
    failures.add(1);
    console.error(`실패 ${res.status}: ${String(res.body).slice(0, 200)}`);
  }
}
