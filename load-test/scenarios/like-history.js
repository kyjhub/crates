// 변수 하나만: 1인당 좋아요 이력.
//
// 미리 준비된 보드에만 좋아요한다. board 행 수는 고정되고 사용자의 좋아요 이력만 자란다.
//
// 준비:
//   ./load-test/seed.sh <USERS> <POOL> 0      좋아요 0건인 계정과 보드 풀을 만든다
//
// 이 시나리오의 관심사는 k6의 처리율이 아니다. 취향 벡터 재계산이 O(이력)이라는 것이
// 어디서 문제가 되는지를 보려는 것이므로, 실행 후 앱 지표를 함께 읽어야 한다.
//
//   crates_user_vector_recalculation_seconds{quantile="0.95"}
//   crates_user_vector_recalculation_boards        훑은 보드 수 분포
//
// 이력이 이미 쌓인 상태에서의 비용을 보려면 seed.sh의 세 번째 인자로 미리 채워둔다.
//   ./load-test/seed.sh 10 500 150            1인당 150건에서 시작
//
// 주의: 한 사용자가 같은 보드를 두 번 좋아요하면 서버가 멱등 처리해 이력이 늘지 않는다.
// 그러면 시나리오가 무의미해지므로, 풀이 사용자당 반복 수보다 커야 한다.

import { check } from 'k6';
import { likeBoard, login, publicBoardIds } from '../lib/api.js';
import { standard } from '../lib/options.js';

const USERS = Number(__ENV.USERS || 10);
const ITERATIONS = Number(__ENV.ITERATIONS || 600);
/** 좋아요 대상 보드 풀. seed.sh로 만들어둔 보드 수 이하여야 한다. */
const POOL = Number(__ENV.POOL || 500);

export const options = standard({ vus: USERS, iterations: ITERATIONS });

export function setup() {
  // 보드를 만들지 않는다. seed.sh가 만들어둔 것을 읽기만 한다.
  const reader = login('u0');
  const boardIds = publicBoardIds(reader, POOL);
  if (boardIds.length === 0) {
    throw new Error('보드 풀이 비어 있습니다. ./load-test/seed.sh <USERS> <POOL> 1 로 준비하세요.');
  }

  const perUser = Math.ceil(ITERATIONS / USERS);
  if (boardIds.length < perUser) {
    throw new Error(
      `보드 풀(${boardIds.length})이 사용자당 반복 수(${perUser})보다 작습니다. ` +
      `중복 좋아요가 멱등 처리되어 이력이 자라지 않습니다. POOL을 늘리세요.`,
    );
  }

  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    tokens.push(login(`u${i}`));
  }
  return { tokens, boardIds };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  // VU마다 풀의 다른 지점에서 시작해, 같은 보드를 동시에 누르는 것을 줄인다.
  const offset = (__VU - 1) * 97;   // 97은 풀 크기와 서로소가 되기 쉬운 소수
  const boardId = data.boardIds[(offset + __ITER) % data.boardIds.length];
  check(likeBoard(token, boardId), { '좋아요 200': (r) => r.status === 200 });
}
