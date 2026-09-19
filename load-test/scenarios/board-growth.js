// 변수 하나만: board 행 수.
//
// 좋아요를 누른 뒤 곧바로 취소한다. 보드는 남고(board 행 증가) 좋아요 이력은 0으로 돌아간다.
// 그래서 취향 벡터 재계산이 O(이력)으로 커지는 효과가 빠지고, board가 커질 때
// signature 조회와 INSERT 경로가 어떻게 되는지만 남는다.
//
// 좋아요/취소 각각이 재계산을 유발하지만 이력이 0~1건이라 비용이 거의 없다.
//
// 읽는 법: run-measure.sh 로 여러 번 돌리되, 매 실행 전에 cleanup 하지 **않는다**.
// board가 누적되면서 처리율이 어떻게 변하는지를 본다.

import { check } from 'k6';
import { likeNewBoard, login, unlikeBoard, PREFIX } from '../lib/api.js';
import { standard } from '../lib/options.js';

const USERS = Number(__ENV.USERS || 20);
const ITERATIONS = Number(__ENV.ITERATIONS || 600);

export const options = standard({ vus: USERS, iterations: ITERATIONS });

// 준비: ./load-test/seed.sh <USERS> <보드> 0
export function setup() {
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    tokens.push(login(`u${i}`));
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];

  const liked = likeNewBoard(token, `${PREFIX}-growth-${__VU}-${__ITER}`);
  const ok = check(liked, { '좋아요 200': (r) => r.status === 200 });
  if (!ok) {
    return;
  }

  // 취소해 이력을 되돌린다. 이게 없으면 board와 이력이 함께 자라 변수 분리가 깨진다.
  const boardId = liked.json('data.boardId');
  check(unlikeBoard(token, boardId), { '취소 200': (r) => r.status === 200 });
}
