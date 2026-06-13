package com.crates.crates.service.strategy;

import com.crates.crates.DTO.ContentDetailResponse;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.contents.Book;
import com.crates.crates.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookDetailStrategy implements ContentDetailStrategy {

    private final BookRepository bookRepository;

    @Override
    public String getSupportedType() {
        return Book.DTYPE;
    }

    @Override
    public ContentDetailResponse getDetail(Long contentId) {
        return bookRepository.findBookDetailById(contentId)
                .orElseThrow(() -> new BusinessException("도서를 찾을 수 없습니다. id: " + contentId));
    }
}
