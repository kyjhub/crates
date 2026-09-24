package com.crates.crates.entity.contents;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Year;

@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "dtype")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public abstract class Content {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dtype", insertable = false, updatable = false)
    private String dtype;

    /**
     * 원본 데이터셋에서의 id. book은 asin, movie는 imdbId, music은 Spotify track id.
     *
     * <p>AI 서버와 벡터 CSV가 콘텐츠를 가리키는 id다. Qdrant는 content.id로 저장하므로,
     * 벡터를 적재하거나 AI 서버가 벡터를 고쳐 보낼 때 이 값으로 content.id를 찾는다.
     * 컬럼과 (dtype, source_key) 유일성은 V0_1이 만든다.</p>
     */
    @Column(nullable = false, updatable = false)
    private String sourceKey;

    private String s3ObjectKey;
    private String imageExtension;
    private String title;
    private Year releaseYear;
}
