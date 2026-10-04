// 콘텐츠 상세. 기본키 조회라 가장 가벼운 축이다. 다른 API와 비교할 바닥값으로 둔다.
//
// 상세 경로에는 종류(dtype)가 들어가는데 id만 보고는 알 수 없다. setup에서 무작위 id 300개의
// 요약을 받아 종류를 알아 둔다. 같은 300개를 반복하므로 캐시에 유리한 쪽으로 치우친다.
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { anyToken, pick } from '../lib/users.js';
import { MAX_CONTENT_ID, contentDetail, contentSummary } from '../lib/api.js';

export const options = arrival({ p95: 500 });

export function setup() {
  const contents = [];
  for (let i = 0; i < 300; i++) {
    const id = 1 + Math.floor(Math.random() * MAX_CONTENT_ID);
    const res = contentSummary(anyToken(), id);
    if (res.status === 200) contents.push({ id, dtype: res.json('data.contentType') });
  }
  if (contents.length === 0) throw new Error('콘텐츠 요약을 하나도 받지 못했습니다');
  return { contents };
}

export default function ({ contents }) {
  const c = contents[Math.floor(Math.random() * contents.length)];
  record(contentDetail(pick(), c.dtype, c.id));
}
