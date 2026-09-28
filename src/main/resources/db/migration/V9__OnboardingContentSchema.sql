-- 가입 직후 취향 콘텐츠 후보.
--
-- 가입한 사용자는 이 목록에서 1~10개를 골라 첫 취향 벡터를 만든다(UserVectorService.initialize).
-- 제목 검색 대신 종류별 인기순 목록을 스크롤하며 고르게 해서, 무엇을 검색할지 모르는 사용자도 바로 고를 수 있다.
-- 목록은 data/popular_top200_ids.csv(종류별 인기순 200개)에서 V10이 채운다.
--
-- 콘텐츠 하나는 후보에 한 번만 들어가므로 content_id가 곧 기본키다(OnboardingContent의 @MapsId).
-- 종류(dtype)는 content에 있어 여기 두지 않는다. 600행이라 종류별 조회도 조인으로 충분하다.
--
-- 이 파일을 V1(콘텐츠 스키마)에 합치지 않고 새 버전으로 둔 이유: 적용된 파일을 고치면 체크섬이 어긋나
-- 기존 DB가 기동하지 못하고 docker compose down -v가 필요해진다. 부하 측정용으로 쌓아 둔 데이터
-- (좋아요 1,000만 건과 재계산한 취향 벡터)를 지키려고 새 버전으로 추가했다. 다음에 DB를 처음부터
-- 만들 때 기능별 파일로 합쳐도 된다.

create table onboarding_content (
    content_id      bigint  not null,
    -- 같은 종류 안에서의 인기 순위(1부터). 화면은 이 순서대로 보여준다.
    -- CSV의 원래 순위를 그대로 둔다. 벡터가 없어 시딩되지 않은 콘텐츠의 자리는 비어 있을 수 있다.
    popularity_rank integer not null,
    primary key (content_id)
);

alter table onboarding_content
    add constraint fk_onboarding_content_content foreign key (content_id) references content;
