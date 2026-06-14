package com.crates.crates.service;

import com.crates.crates.DTO.BookResponseDTO;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookService {

    private final BookRepository bookRepository;

    public BookResponseDTO getDetail(Long contentId) {
        return bookRepository.findBookDetailById(contentId)
                .orElseThrow(() -> new BusinessException("도서를 찾을 수 없습니다. id: " + contentId));
    }
}
