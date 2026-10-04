// 홈 추천 보드 4개. 취향 벡터 조회 → Qdrant 콘텐츠 검색(32건, 벡터 포함) → 보드마다 signature 조회 →
// 저장되지 않은 보드는 제목 query 매칭(Qdrant query_vector 검색). 미저장이면 Qdrant 검색이 5번이다.
//
//   RATE=20 k6 run load-test/scenarios/api-recommend.js      (보통은 run-sweep.sh로)
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { pick } from '../lib/users.js';
import { recommendation } from '../lib/api.js';

export const options = arrival({ p95: 500 });

export default function () {
  record(recommendation(pick()));
}
