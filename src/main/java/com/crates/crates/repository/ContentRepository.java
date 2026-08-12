package com.crates.crates.repository;

import com.crates.crates.DTO.ContentQueryDto;
import com.crates.crates.entity.contents.Content;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContentRepository extends JpaRepository<Content, Long> {

    @Query("SELECT new com.crates.crates.DTO.ContentQueryDto(" +
            "c.id, c.title, c.s3ObjectKey, c.imageExtension, c.dtype, c.releaseYear) " +
            "FROM Content c WHERE c.id IN :ids")
    List<ContentQueryDto> findContentsByIds(@Param("ids") List<Long> ids);

    @Query("SELECT new com.crates.crates.DTO.ContentQueryDto(" +
            "c.id, c.title, c.s3ObjectKey, c.imageExtension, c.dtype, c.releaseYear) " +
            "FROM Content c WHERE c.id = :id")
    Optional<ContentQueryDto> findContentSummaryById(@Param("id") Long id);
}
