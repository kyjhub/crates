// 검색어 풀. 검색 보드(AI 임베딩)와 콘텐츠 제목 검색이 같이 쓴다.
//
// 같은 검색어만 반복하면 Qdrant·PostgreSQL 캐시에 한 결과만 올라 실제보다 빠르다. 장르·분위기·제목 조각을
// 섞어 100개를 두었다. 콘텐츠 제목이 대부분 영어라(도서 원본이 Amazon 데이터) 영어 단어도 섞는다.

export const KEYWORDS = [
  '재즈', '힙합', '클래식', '록', '발라드', '인디', '시티팝', '보사노바', '로파이', '피아노',
  '공포', '로맨스', '스릴러', '코미디', '다큐멘터리', '애니메이션', 'SF', '판타지', '전쟁', '범죄',
  '추리소설', '자기계발', '역사', '철학', '심리학', '경제', '여행', '요리', '에세이', '시집',
  '비 오는 날', '새벽 감성', '드라이브', '공부할 때', '운동할 때', '잠들기 전', '여름 밤', '겨울 아침', '퇴근길', '주말 오후',
  '우주', '바다', '사막', '도시', '숲', '기차', '고양이', '강아지', '첫사랑', '이별',
  '성장', '우정', '가족', '복수', '모험', '생존', '시간여행', '로봇', '인공지능', '마법',
  'love', 'war', 'night', 'dream', 'blue', 'summer', 'king', 'girl', 'life', 'world',
  'jazz', 'rock', 'soul', 'blues', 'piano', 'guitar', 'live', 'best', 'greatest', 'christmas',
  'history', 'murder', 'secret', 'dark', 'star', 'city', 'road', 'river', 'house', 'garden',
  'mystery', 'romance', 'horror', 'fantasy', 'science', 'cook', 'travel', 'poetry', 'family', 'children',
];

export function randomKeyword() {
  return KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
}
