-- 보드의 소유/공개/중복 규칙을 DB가 강제한다.
--
-- JPA 애노테이션으로는 표현할 수 없는 것들이라(부분 유니크 인덱스, CHECK, ON DELETE 동작,
-- DEFERRABLE) 마이그레이션으로 관리한다. Flyway는 실패하면 앱이 뜨지 않으므로, 제약이 안 걸린 채
-- 조용히 돌아가는 상황이 생기지 않는다.
--
-- V4__SeedBoards 다음에 실행된다. 시딩 데이터가 이 규칙을 어기면 여기서 바로 드러난다.
-- 설계 근거는 docs/board-schema.md 참고.
--
-- 항상 빈 DB에서 시작하므로(docker compose down -v) DROP 없이 추가만 한다.

-- ─────────────────────────────────────────────────────────────
-- board_feedback: 사용자가 탈퇴해도 좋아요 집계는 남긴다
-- ─────────────────────────────────────────────────────────────
-- 여기만 DROP이 필요하다. 이전 마이그레이션의 잔재가 아니라, BoardFeedback의
-- @ForeignKey(name = "fk_feedback_user")를 보고 Hibernate가 이미 만들어둔 FK다.
-- 애노테이션으로는 ON DELETE 동작을 줄 수 없어서 지우고 다시 건다.
ALTER TABLE board_feedback
    DROP CONSTRAINT IF EXISTS fk_feedback_user;

ALTER TABLE board_feedback
    ADD CONSTRAINT fk_feedback_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE SET NULL;

-- ─────────────────────────────────────────────────────────────
-- board: 소유 / 공개 규칙
-- ─────────────────────────────────────────────────────────────

-- USER_CUSTOM이면 소유자 필수, 나머지 타입은 반드시 NULL(전역 공용).
ALTER TABLE board
    ADD CONSTRAINT ck_board_owner
        CHECK ((board_type = 'USER_CUSTOM') = (user_id IS NOT NULL));

-- PRIVATE은 소유자가 있는 USER_CUSTOM만 가질 수 있다.
ALTER TABLE board
    ADD CONSTRAINT ck_board_visibility
        CHECK (visibility = 'PUBLIC' OR board_type = 'USER_CUSTOM');

-- ─────────────────────────────────────────────────────────────
-- board: 콘텐츠 구성(content_signature) 기준 중복 방지
-- ─────────────────────────────────────────────────────────────

-- AI_RECOMMEND는 소유자가 없는 전역 공용 보드다. 콘텐츠 구성이 같으면 같은 보드이며,
-- 하나로 모아야 좋아요가 여러 행으로 분산되지 않는다.
CREATE UNIQUE INDEX uk_board_ai_signature ON board (content_signature)
    WHERE board_type = 'AI_RECOMMEND' AND deleted_at IS NULL;

-- USER_CUSTOM은 사용자 1명당 같은 구성의 보드를 1개만 가질 수 있다.
-- (다른 사용자가 같은 구성을 갖는 것은 허용한다. 제목과 의도가 다른 별개의 창작물이다.)
CREATE UNIQUE INDEX uk_board_user_signature ON board (user_id, content_signature)
    WHERE board_type = 'USER_CUSTOM' AND deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────
-- board: 조회 인덱스
-- ─────────────────────────────────────────────────────────────

-- 인기 보드. 정렬을 인덱스로 해결하고, id를 포함해 동률에서도 순서가 확정되게 한다.
-- like_count는 초기에 0~2에 몰려 동률이 대량으로 생기는데, id가 없으면 LIMIT 경계에서
-- 어떤 보드가 잘리는지가 실행마다 달라진다.
CREATE INDEX idx_board_popular ON board (like_count DESC, id)
    WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;

-- 내가 만든 보드 / 나에게 귀속된 보드
CREATE INDEX idx_board_owner ON board (user_id, board_type)
    WHERE deleted_at IS NULL;

-- ─────────────────────────────────────────────────────────────
-- board_item: 슬롯 규칙 (2행 4열, 1~8)
-- ─────────────────────────────────────────────────────────────

ALTER TABLE board_item
    ADD CONSTRAINT ck_board_item_slot
        CHECK (slot_no BETWEEN 1 AND 8);

-- 아래 두 개는 인덱스가 아니라 DEFERRABLE 제약이다. 검사 시점이 문장 단위가 아니라
-- 커밋 시점이어야 하기 때문이다.
--
-- 보드 수정은 콘텐츠 8건을 통째로 교체한다. 그 과정에서 A와 B의 슬롯을 맞바꾸거나, 기존 행을
-- 지우고 같은 콘텐츠를 다시 넣는 중간 상태가 반드시 생긴다. 문장 단위로 검사하면 최종 결과가
-- 멀쩡해도 중간 상태에서 걸려 수정이 통째로 실패한다.
-- (Hibernate가 DELETE와 INSERT를 어떤 순서로 내보내는지에 기대지 않으려는 목적도 있다.)
--
-- 커밋 시점에는 그대로 검사되므로 진짜 중복은 여전히 거부된다.
-- UNIQUE 제약은 인덱스를 함께 만들기 때문에 조회 성능도 그대로다.

-- 한 보드에 같은 슬롯이 둘이면 렌더링이 깨진다.
ALTER TABLE board_item
    ADD CONSTRAINT uk_board_item_slot UNIQUE (board_id, slot_no)
        DEFERRABLE INITIALLY DEFERRED;

-- 한 보드에 같은 콘텐츠가 두 번 들어가면 content_signature가 "1,1,2,..."가 되어
-- 8건 규칙이 무너지고 슬롯 하나가 중복 포스터로 채워진다.
ALTER TABLE board_item
    ADD CONSTRAINT uk_board_item_content UNIQUE (board_id, content_id)
        DEFERRABLE INITIALLY DEFERRED;
