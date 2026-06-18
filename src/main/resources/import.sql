ALTER TABLE board_feedback
    DROP CONSTRAINT IF EXISTS fk_feedback_user;

ALTER TABLE board_feedback
    ADD CONSTRAINT fk_feedback_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE SET NULL;
