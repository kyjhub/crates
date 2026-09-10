package com.crates.crates.DTO;

import java.util.List;

/**
 * 무한 스크롤용 페이지 응답.
 *
 * <p>Spring의 {@code Page<T>}를 그대로 직렬화하지 않는 이유는 두 가지다. Boot 3.3+에서
 * 직렬화 형태가 안정적이지 않다는 경고가 뜨고, 프론트가 쓰지 않는 필드(pageable, sort,
 * numberOfElements 등)가 응답에 잔뜩 붙는다.</p>
 *
 * <p>전체 건수를 담지 않는다. {@code hasNext}는 요청한 것보다 한 건 더 조회해서 초과분이
 * 있는지 보는 것만으로 알 수 있어 count 쿼리가 아예 필요 없다. 화면에 "총 N개"를 표시하게
 * 되면 그때 totalElements를 추가한다.</p>
 *
 * @param page 0부터 시작하는 현재 페이지 번호
 * @param size 요청한 페이지 크기. content의 실제 크기는 마지막 페이지에서 이보다 작다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        boolean hasNext
) {
}
