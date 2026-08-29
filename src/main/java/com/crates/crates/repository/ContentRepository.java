package com.crates.crates.repository;

import com.crates.crates.DTO.ContentQueryDto;
import com.crates.crates.entity.contents.Content;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContentRepository extends JpaRepository<Content, Long> {

    // 대량의 content ID를 메모리에 한 번에 올리지 않도록 마지막 ID 이후의 데이터만 배치 조회한다.
    @Query("SELECT c.id FROM Content c WHERE c.id > :afterId ORDER BY c.id")
    List<Long> findContentIdsAfter(@Param("afterId") Long afterId, Pageable pageable);

    @Query("SELECT new com.crates.crates.DTO.ContentQueryDto(" +
            "c.id, c.title, c.s3ObjectKey, c.imageExtension, c.dtype, c.releaseYear) " +
            "FROM Content c WHERE c.id IN :ids")
    List<ContentQueryDto> findContentsByIds(@Param("ids") List<Long> ids);

    @Query("SELECT new com.crates.crates.DTO.ContentQueryDto(" +
            "c.id, c.title, c.s3ObjectKey, c.imageExtension, c.dtype, c.releaseYear) " +
            "FROM Content c WHERE c.id = :id")
    Optional<ContentQueryDto> findContentSummaryById(@Param("id") Long id);
}
