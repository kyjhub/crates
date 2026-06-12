package com.crates.crates.Global;

import com.crates.crates.entity.board.Board;
import com.crates.crates.entity.contents.Movie;
import com.crates.crates.enumData.BoardType;
import com.crates.crates.repository.BoardRepository;
import com.crates.crates.repository.ContentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInit {

    private final ContentRepository contentRepository;
    private final BoardRepository boardRepository;

    @PostConstruct
    @Transactional
    public void init() {
        // 이미 데이터가 있다면 초기화를 건너뜁니다 (서버 재시작 시 중복 생성 방지)
        if (boardRepository.count() > 0) {
            log.info("[DataInit] 초기 데이터가 이미 존재합니다. 스킵합니다.");
            return;
        }

        log.info("[DataInit] 테스트용 초기 데이터(Movie, Board) 생성을 시작합니다...");

        // 1. Content (Movie) 데이터 생성
        Movie movie1 = Movie.builder()
                .title("인셉션")
                .s3ObjectKey("movies/inception")
                .imageExtension("jpeg")
                .releaseDate(LocalDate.of(2010, 7, 21))
                .genre(List.of("SF", "액션", "스릴러"))
                .director(List.of("크리스토퍼 놀란"))
                .actor(List.of("레오나르도 디카프리오", "조셉 고든 레빗"))
                .runningTime(148)
                .plot("타인의 꿈에 들어가 생각을 훔친다.")
                .build();

        Movie movie2 = Movie.builder()
                .title("인터스텔라")
                .s3ObjectKey("movies/interstellar")
                .imageExtension("webp")
                .releaseDate(LocalDate.of(2014, 11, 6))
                .genre(List.of("SF", "드라마"))
                .director(List.of("크리스토퍼 놀란"))
                .actor(List.of("매튜 맥커너히", "앤 해서웨이"))
                .runningTime(169)
                .plot("우린 답을 찾을 것이다. 늘 그랬듯이.")
                .build();

        Movie movie3 = Movie.builder()
                .title("매트릭스")
                .s3ObjectKey("movies/matrix")
                .imageExtension("jpg")
                .releaseDate(LocalDate.of(1999, 5, 15))
                .genre(List.of("SF", "액션"))
                .director(List.of("워쇼스키 자매"))
                .actor(List.of("키아누 리브스"))
                .runningTime(136)
                .plot("당신이 사는 세상이 가짜라면?")
                .build();

        // Content 저장
        contentRepository.saveAll(List.of(movie1, movie2, movie3));

        // 2. Board 생성
        Board board = Board.builder()
                .boardType(BoardType.PRE_MADE) // 예시 타입
                .likeCount(0L)
                .build();

        // 3. BoardItem 연결 (양방향 연관관계 편의 메서드 사용)
        // 우리가 아까 확인했던 addContent() 메서드를 활용합니다.
        board.addContent(movie1);
        board.addContent(movie2);
        board.addContent(movie3);

        // 4. Board 저장 (CascadeType.ALL 덕분에 BoardItem도 함께 저장됨!)
        boardRepository.save(board);

        log.info("[DataInit] 테스트용 초기 데이터 생성 완료! (Board ID: {})", board.getId());
    }
}
