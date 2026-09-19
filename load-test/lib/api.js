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
 * 미리 준비된 테스트 계정으로 로그인한다.
 *
 * **계정을 만들지 않는다.** 데이터 준비는 load-test/seed.sh 의 몫이다.
 * 예전에는 여기서 회원가입까지 했는데, 그러면 부하 생성기가 상태를 만들게 되어
 * 실행마다 출발점이 달라지고 비교가 깨진다(docs 4-9). 없으면 바로 실패시킨다 —
 * 조용히 만들어내면 같은 문제가 되돌아온다.
 */
export function login(tag) {
  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ loginId: `${PREFIX}_${tag}`, pwd: 'Loadtest!234' }),
    { headers: JSON_HEADERS },
  );
  if (res.status !== 200) {
    throw new Error(
      `계정 ${PREFIX}_${tag} 로그인 실패(${res.status}). ` +
      `먼저 ./load-test/seed.sh 로 데이터를 준비하세요.`,
    );
  }
  return res.json('data.accessToken');
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

/** 좋아요 취소. 좋아요 이력을 0으로 되돌려야 하는 시나리오(board-growth)에서 쓴다. */
export function unlikeBoard(token, boardId) {
  return http.del(`${BASE}/api/boards/${boardId}/likes`, null, {
    ...auth(token),
    tags: { name: 'DELETE /api/boards/{id}/likes' },
  });
}

/**
 * 공개 보드 목록에서 boardId만 골라낸다. seed.sh가 만들어둔 보드 풀을 집어올 때 쓴다.
 *
 * 보관함(/api/boards/mine)이 아니라 이 경로를 쓰는 이유: 보관함은 "내가 만들었거나
 * 좋아요한" 보드만 준다. 좋아요 0건으로 시딩한 계정에는 빈 목록이 돌아온다.
 * 인기 보드는 소유와 무관하게 공개 보드를 주므로 풀 전체가 보인다.
 */
export function publicBoardIds(token, count) {
  const res = http.get(`${BASE}/api/boards/liked?n=${count}`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/liked' },
  });
  if (res.status !== 200) {
    throw new Error(`보드 풀 조회 실패: ${res.status} ${String(res.body).slice(0, 200)}`);
  }
  return res.json('data').map((b) => b.boardId).filter((id) => id !== null);
}

/** 내 보관함에서 boardId 목록만. */
export function myBoardIds(token, size) {
  const res = http.get(`${BASE}/api/boards/mine?filter=ALL&page=0&size=${size}`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/mine' },
  });
  if (res.status !== 200) {
    throw new Error(`보관함 조회 실패: ${res.status} ${String(res.body).slice(0, 200)}`);
  }
  return res.json('data.content').map((b) => b.boardId).filter((id) => id !== null);
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
