// 변수 하나만: 1인당 좋아요 이력.
//
// setup에서 보드 풀을 미리 만들어두고, 측정 구간에서는 **이미 있는 보드**에만 좋아요한다.
// board 행 수는 고정되고 사용자의 좋아요 이력만 자란다.
//
// 이 시나리오의 관심사는 k6의 처리율이 아니다. 취향 벡터 재계산이 O(이력)이라는 것이
// 어디서 문제가 되는지를 보려는 것이므로, 실행 후 앱 지표를 함께 읽어야 한다.
//
//   crates_user_vector_recalculation_seconds{quantile="0.95"}
//   crates_user_vector_recalculation_boards   (훑은 보드 수 분포)
//
// 주의: 한 사용자가 같은 보드를 두 번 좋아요할 수 없다(uk_board_feedback_board_user).
// 그래서 풀 크기가 사용자당 반복 수보다 커야 한다.

import { check } from 'k6';
import { ensureUser, likeBoard, likeNewBoard, myBoardIds, PREFIX } from '../lib/api.js';
import { standard } from '../lib/options.js';

const USERS = Number(__ENV.USERS || 10);
const ITERATIONS = Number(__ENV.ITERATIONS || 600);
/** 좋아요 대상 보드 풀. 사용자당 반복 수보다 넉넉해야 중복 좋아요가 안 난다. */
const POOL = Number(__ENV.POOL || Math.ceil(ITERATIONS / USERS) + 20);

export const options = standard({ vus: USERS, iterations: ITERATIONS });

export function setup() {
  // 풀을 만드는 전용 계정. 이 계정의 이력은 측정 대상이 아니다.
  const seeder = ensureUser('pool-seeder');
  for (let i = 0; i < POOL; i++) {
    likeNewBoard(seeder, `${PREFIX}-pool-${i}`);
  }
  const boardIds = myBoardIds(seeder, POOL);
  if (boardIds.length < POOL) {
    throw new Error(`보드 풀이 부족합니다: ${boardIds.length} < ${POOL}`);
  }

  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    tokens.push(ensureUser(`h${i}`));
  }
  return { tokens, boardIds };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  // VU마다 풀의 다른 구간을 쓴다. 같은 보드를 두 번 누르면 409가 아니라 멱등 처리되지만,
  // 그러면 좋아요 이력이 자라지 않아 시나리오가 무의미해진다.
  const boardId = data.boardIds[__ITER % data.boardIds.length];
  check(likeBoard(token, boardId), { '좋아요 200': (r) => r.status === 200 });
}
