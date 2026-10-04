// 홈 인기 보드 8개. idx_board_popular로 상위 8행만 읽는다. 계정과 무관하게 같은 결과라 캐시에 가장 유리하다.
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { pick } from '../lib/users.js';
import { popular } from '../lib/api.js';

export const options = arrival({ p95: 500 });

export default function () {
  record(popular(pick()));
}
