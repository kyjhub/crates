-- 콘텐츠 제목 검색.
--
-- 보드를 수정할 때와 가입 직후 취향 콘텐츠를 고를 때, 사용자가 제목을 입력해 콘텐츠를 찾는다.
-- 제목은 전부 영어이고 단어 일부만 입력해도(자동완성) 걸려야 하므로 부분 문자열 검색이 필요하다.
--
-- pg_trgm을 쓰는 이유:
--   - 전문 검색(tsvector)은 단어 단위라 "harr" 같은 부분 입력을 그냥은 못 잡는다.
--   - LIKE '%...%'는 보통 인덱스를 못 타지만, GIN + gin_trgm_ops로는 탄다.
--
-- 시딩(V5~V7) 뒤에 둔다. GIN 인덱스는 행을 하나씩 넣으며 갱신하는 것보다 다 넣은 뒤 한 번에
-- 만드는 편이 훨씬 빠르다.
--
-- 실측 (콘텐츠 191,239건, PostgreSQL 15):
--   인덱스 생성 765ms, 크기 14MB (테이블 19MB)
--   "har"(3,579건 매칭) 17ms / "harry potter" 1ms / "the"(62,004건 매칭) 116ms
--
-- 주의: 트라이그램은 3글자 단위라 검색어가 3글자 미만이면 인덱스를 쓸 수 없고 Seq Scan으로
-- 떨어진다(2글자 155ms, 1글자 228ms). 그래서 API에서 최소 3글자를 강제한다.

create extension if not exists pg_trgm;

create index idx_content_title_trgm on content using gin (title gin_trgm_ops);
