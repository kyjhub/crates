// 기본 처리율 시나리오 — 저장되지 않은 보드에 좋아요(= 보드 생성 + 좋아요).
//
// 한 번의 반복이 건드리는 것
//   findActiveByTypeAndSignature (signature 조회) / board·board_item·board_feedback INSERT
//   그리고 커밋 후 비동기 취향 벡터 재계산
//
// 주의: 이 시나리오는 board 행 수와 1인당 좋아요 이력을 **동시에** 늘린다. 그래서
// "규모가 커질 때 무엇이 느려지는가"를 이걸로 물으면 답할 수 없다. 두 효과가 섞인다.
// 변수를 하나만 바꾸려면 board-growth.js / like-history.js 를 쓸 것.
//
// 이 시나리오의 쓸모는 "같은 출발점에서의 정상 상태 처리율"이다. 코드를 바꾸기 전후로
// run-measure.sh 를 돌려 중앙값을 비교하는 용도다.

import { check } from 'k6';
import { ensureUser, likeNewBoard, PREFIX } from '../lib/api.js';
import { standard } from '../lib/options.js';

const USERS = Number(__ENV.USERS || 20);
const ITERATIONS = Number(__ENV.ITERATIONS || 600);

export const options = standard({ vus: USERS, iterations: ITERATIONS });

export function setup() {
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    tokens.push(ensureUser(`u${i}`));
  }
  return { tokens };
}

export default function (data) {
  // VU 번호로 계정을 고정한다. 계정마다 좋아요 이력이 다르면 재계산 비용이 달라져
  // 실행 간 비교가 흔들린다.
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const res = likeNewBoard(token, `${PREFIX}-new-${__VU}-${__ITER}`);
  check(res, { '좋아요 200': (r) => r.status === 200 });
}
