// Crates API 호출 묶음. 시나리오는 이 파일만 알면 된다.
//
// 부하 생성기는 "밖에서 본 시간"만 잰다. 앱 내부(재계산 소요, 커넥션 풀 대기)는
// /actuator/prometheus 에서, DB 쪽은 pg_stat_statements 에서 따로 본다.
// 세 층을 한 도구로 보려 하지 말 것 — docs/load-test-and-metrics.md 참고.

import http from 'k6/http';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';

/** 보드 하나에 담기는 콘텐츠 수. 백엔드 Board.ITEMS_PER_BOARD와 같은 값이어야 한다. */
export const ITEMS_PER_BOARD = 8;

/** content.id 상한. 시딩 결과가 바뀌면 같이 바꿀 것 (기본: BOOK 111,094 + MOVIE 40,109 + MUSIC 40,036). */
export const MAX_CONTENT_ID = Number(__ENV.MAX_CONTENT_ID || 191239);

/** 테스트로 만든 데이터를 나중에 골라내기 위한 접두사. cleanup.sql이 이 값을 쓴다. */
export const PREFIX = 'loadtest';

const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' };

function auth(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

/**
 * 테스트 계정 확보. 이미 있으면 로그인으로 넘어간다.
 *
 * setup()에서만 부른다. VU 안에서 부르면 회원가입 비용이 측정에 섞이고,
 * 계정마다 취향 벡터 초기화까지 돌아 수치가 흐려진다.
 */
export function ensureUser(tag) {
  const body = JSON.stringify({
    loginId: `${PREFIX}_${tag}`,
    pwd: 'Loadtest!234',
    email: `${PREFIX}_${tag}@example.com`,
    nickname: `${PREFIX}_${tag}`,
    gender: 'OTHER',
    birthYear: '1995-01-01',
  });

  const signup = http.post(`${BASE}/api/auth/signup`, body, { headers: JSON_HEADERS });
  if (signup.status === 200 || signup.status === 201) {
    return signup.json('data.accessToken');
  }

  const login = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ loginId: `${PREFIX}_${tag}`, pwd: 'Loadtest!234' }),
    { headers: JSON_HEADERS },
  );
  if (login.status !== 200) {
    throw new Error(`계정 준비 실패 ${tag}: signup=${signup.status} login=${login.status} ${login.body}`);
  }
  return login.json('data.accessToken');
}

/** 서로 다른 콘텐츠 8건. 중복이 섞이면 서버가 400으로 거절한다. */
export function randomContentIds() {
  const picked = new Set();
  while (picked.size < ITEMS_PER_BOARD) {
    picked.add(1 + Math.floor(Math.random() * MAX_CONTENT_ID));
  }
  return [...picked];
}

/**
 * 아직 저장되지 않은 보드에 좋아요. 보드 생성 + 좋아요가 한 번에 일어난다.
 *
 * 이 경로가 findActiveByTypeAndSignature를 부르므로 V7의 idx_board_signature_lookup을
 * 검증하는 자리다. 호출할 때마다 board 행이 하나 늘어난다.
 */
export function likeNewBoard(token, title) {
  return http.post(
    `${BASE}/api/boards/likes`,
    JSON.stringify({ title, contentIds: randomContentIds() }),
    { ...auth(token), tags: { name: 'POST /api/boards/likes' } },
  );
}

/** 이미 저장된 보드에 좋아요. signature 조회가 없는 순수 좋아요 경로. */
export function likeBoard(token, boardId) {
  return http.post(`${BASE}/api/boards/${boardId}/likes`, null, {
    ...auth(token),
    tags: { name: 'POST /api/boards/{id}/likes' },
  });
}

/** 오늘의 추천 보드. resolveGeneratedBoard를 4번 부르므로 signature 조회가 4회 일어난다. */
export function recommendation(token) {
  return http.get(`${BASE}/api/boards/recommendation/user`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/recommendation/user' },
  });
}

/** 보관함. filter는 ALL | LIKED | CREATED. */
export function archive(token, filter, page, size) {
  return http.get(`${BASE}/api/boards/mine?filter=${filter}&page=${page}&size=${size}`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/mine' },
  });
}
