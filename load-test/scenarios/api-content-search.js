// 콘텐츠 제목 검색(보드 수정 화면). idx_content_title_trgm(pg_trgm GIN)을 탄다. AI 서버는 거치지 않는다.
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { pick } from '../lib/users.js';
import { KEYWORDS } from '../lib/keywords.js';
import { searchContents } from '../lib/api.js';

export const options = arrival({ p95: 500 });

// 제목 검색은 trigram이라 3글자 미만이면 서버가 400으로 거절한다("검색어는 3글자 이상 입력해주세요").
// 화면도 그 전에 막으므로 실제로 도착하는 검색어만 남긴다.
const LONG_KEYWORDS = KEYWORDS.filter((k) => [...k].length >= 3);

export default function () {
  record(searchContents(pick(), LONG_KEYWORDS[Math.floor(Math.random() * LONG_KEYWORDS.length)]));
}
