package com.crates.crates.entity.user;

import com.crates.crates.entity.board.BoardFeedback;
import com.crates.crates.enumData.Gender;
import com.crates.crates.enumData.LoginType;
import com.crates.crates.enumData.ROLE;
import com.crates.crates.enumData.AuthProvider;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_provider_and_id",    // 제약조건 이름
                    columnNames = {"provider", "provider_id"}
            ),
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 구글, kakao, naver 계정이 없는 경우 사용
     */
    private String loginId;    // 로그인ID

    private String pwd;

    @Enumerated(EnumType.STRING)
    private ROLE role;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private LocalDate birthDate;

    private String nickname;        // 중복 불가, 사용자가 직접 설정

    private String email;

    @Enumerated(EnumType.STRING)
    private LoginType loginType;

    @Enumerated(EnumType.STRING)
    private AuthProvider provider; // 예: "GOOGLE", "KAKAO", "NAVER"

    private String providerId; // 구글 등 소셜 플랫폼에서 넘겨준 해당 유저의 고유 식별 ID (예: "sub" 값)

    // 양방향 매핑 ( User -> boardFeedback)
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @Builder.Default
    private List<BoardFeedback> boardFeedbacks = new ArrayList<>();

    // 회원가입시 OAuth 인증 후 필요
    public void updateProfile(String email, String nickname, Gender gender, LocalDate birthYear) {
        this.email = email;
        this.nickname = nickname;
        this.gender = gender;
        this.birthDate = birthYear;
    }
}
