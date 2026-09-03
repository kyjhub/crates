package com.crates.crates.enumData;

/**
 * 보드 공개 범위.
 *
 * <p>인기 보드 목록은 {@code PUBLIC}만 대상으로 한다. {@code PRIVATE}은 소유자가 있는
 * {@code USER_CUSTOM} 보드만 가질 수 있으며, 이 규칙은 import.sql의 ck_board_visibility가
 * DB에서 강제한다.</p>
 */
public enum Visibility {
    PUBLIC, PRIVATE
}
