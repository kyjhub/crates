package com.crates.crates.Global.paging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 오프셋과 개수를 직접 지정하는 Pageable.
 *
 * <p>{@code PageRequest.of(page, size)}는 오프셋을 {@code page * size}로 계산한다. 그래서
 * "한 건 더 가져와 hasNext를 판정하는" 방식과 섞으면 어긋난다. {@code PageRequest.of(page, size + 1)}로
 * 적으면 2페이지의 오프셋이 {@code size}가 아니라 {@code size + 1}이 되어, 경계에 있는 <b>항목 하나가
 * 통째로 건너뛰어진다.</b></p>
 *
 * <pre>
 *   size=20, PageRequest.of(page, 21) 일 때
 *     page 0   오프셋  0, 21건 조회 → 0~19 노출 (20번은 hasNext 판정용으로 버림)
 *     page 1   오프셋 21, 21건 조회 → 21~40 노출        ← 20번이 영영 안 보인다
 * </pre>
 *
 * <p>가져오는 개수(size + 1)와 건너뛰는 개수(page * size)는 서로 다른 값이므로 둘을 따로 받는다.</p>
 */
public record OffsetLimit(long offset, int limit) implements Pageable {

    public OffsetLimit {
        if (offset < 0) throw new IllegalArgumentException("오프셋은 음수일 수 없습니다: " + offset);
        if (limit < 1) throw new IllegalArgumentException("개수는 1 이상이어야 합니다: " + limit);
    }

    /** 페이지 번호와 페이지 크기로부터, hasNext 판정용 한 건을 더해서 만든다. */
    public static OffsetLimit forPageWithLookahead(int page, int size) {
        return new OffsetLimit((long) page * size, size + 1);
    }

    @Override public int getPageNumber()      { return (int) (offset / limit); }
    @Override public int getPageSize()        { return limit; }
    @Override public long getOffset()         { return offset; }
    @Override public Sort getSort()           { return Sort.unsorted(); }
    @Override public Pageable next()          { return new OffsetLimit(offset + limit, limit); }
    @Override public Pageable previousOrFirst(){ return hasPrevious() ? new OffsetLimit(offset - limit, limit) : first(); }
    @Override public Pageable first()         { return new OffsetLimit(0, limit); }
    @Override public Pageable withPage(int pageNumber) { return new OffsetLimit((long) pageNumber * limit, limit); }
    @Override public boolean hasPrevious()    { return offset > 0; }
    @Override public boolean isPaged()        { return true; }
}
