-- content.source_key: 원본 데이터셋에서의 id. book=asin, movie=imdbId, music=Spotify track id.
--
-- AI 서버와 벡터 CSV는 콘텐츠를 이 id로 가리킨다. Qdrant의 point id는 content.id라서, 벡터를
-- 적재하거나(ContentVectorLoader) AI 서버의 수정 요청을 반영하려면 이 값으로 content.id를 찾는다.
-- 이 짝이 RDB에 남아 있으므로 Qdrant가 비워져도(볼륨 없음) RDB를 다시 시딩하지 않고
-- CSV만으로 벡터를 다시 적재할 수 있다.
--
-- 버전이 0.1인 이유: V1~V3 시딩이 이 컬럼에 값을 넣으므로 그보다 먼저 있어야 한다.
-- V0는 고치지 않는다(적용된 DB에서 체크섬이 어긋난다).
--
-- 유일성은 (dtype, source_key)로 건다. 키 체계가 도메인마다 달라 서로 겹치지 않는다는 보장이 없고,
-- 조회도 늘 도메인 하나로 좁혀서 한다. 원본 CSV의 중복(movie 593행, music 354행)은 V2·V3가
-- 넣기 전에 걸러내며, 이 인덱스가 그 규칙을 DB에서도 강제한다.
--
-- 주의: 이미 V1 이상이 적용된 DB에서는 Flyway가 "적용 안 된 낮은 버전"으로 보고 기동을 멈춘다.
-- 이 변경부터는 docker compose down -v 로 처음부터 시딩해야 한다.

ALTER TABLE content ADD COLUMN source_key VARCHAR(255) NOT NULL;

CREATE UNIQUE INDEX ux_content_source_key ON content (dtype, source_key);
