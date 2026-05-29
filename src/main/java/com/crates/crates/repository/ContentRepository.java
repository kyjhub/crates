package com.crates.crates.repository;

import com.crates.crates.DTO.ContentQueryDto;
import com.crates.crates.entity.Contents.Content;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContentRepository extends JpaRepository<Content, Long> {

    // Content+하위컨텐츠 필드에서 Content 필드만 조회
    @Query("SELECT new com.crates.crates.DTO.ContentQueryDto(" +
            "c.id, c.title, c.s3ObjectKey, c.imageExtension, c.dtype, c.releaseDate) " +
            "FROM Content c WHERE c.id IN :ids")
    List<ContentQueryDto> findContentsByIds(@Param("ids") List<Long> ids);
}
