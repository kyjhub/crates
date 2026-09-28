package com.crates.crates.service;

import com.crates.crates.DTO.BoardContentIdDto;
import com.crates.crates.DTO.LikedBoardDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.user.User;
import com.crates.crates.entity.user.UserVector;
import com.crates.crates.enumData.Rating;
import com.crates.crates.repository.BoardFeedbackRepository;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.OnboardingContentRepository;
import com.crates.crates.repository.UserRepository;
import com.crates.crates.repository.UserVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserVectorServiceTest {

    private static final Long USER_ID = 42L;
    private static final int DIMENSION = 768;
    private static final double ALPHA = 0.25;

    @Mock private UserVectorRepository userVectorRepository;
    @Mock private UserRepository userRepository;
    @Mock private BoardFeedbackRepository boardFeedbackRepository;
    @Mock private BoardItemRepository boardItemRepository;
    @Mock private ContentVectorService contentVectorService;
    @Mock private OnboardingContentRepository onboardingContentRepository;
    @Mock private UserVectorMetrics metrics;

    private UserVectorService service;

    @BeforeEach
    void setUp()
    {
        service = new UserVectorService(userVectorRepository, userRepository, boardFeedbackRepository,
                boardItemRepository, contentVectorService, onboardingContentRepository, metrics);
        ReflectionTestUtils.setField(service, "vectorDimension", DIMENSION);
        ReflectionTestUtils.setField(service, "decayAlpha", ALPHA);
    }

    @Nested
    class InitializeFromSelectedContents {

        @Test
        void averageOfSelectedContentsBecomesTheVectorAndIdsAreKept()
        {
            when(userVectorRepository.existsById(USER_ID)).thenReturn(false);
            when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
            when(onboardingContentRepository.countByContentIdIn(Set.of(1L, 2L))).thenReturn(2L);
            when(contentVectorService.findVectors(Set.of(1L, 2L)))
                    .thenReturn(Map.of(1L, axis(0), 2L, axis(1)));

            service.initialize(USER_ID, List.of(1L, 2L));

            ArgumentCaptor<UserVector> saved = ArgumentCaptor.forClass(UserVector.class);
            verify(userVectorRepository).save(saved.capture());
            float[] vector = saved.getValue().getUserVector();

            // 두 축의 평균을 정규화하면 각 축이 1/√2
            assertEquals(1 / Math.sqrt(2), vector[0], 1e-6);
            assertEquals(1 / Math.sqrt(2), vector[1], 1e-6);
            assertEquals(1.0, norm(vector), 1e-6);
            // 좋아요 재계산이 다시 쓸 수 있도록 고른 id를 고른 순서대로 남긴다
            assertEquals(List.of(1L, 2L), saved.getValue().getInitialContentIds());
        }

        @Test
        void rejectsWhenAlreadySelected()
        {
            when(userVectorRepository.existsById(USER_ID)).thenReturn(true);

            assertThrows(BusinessException.class, () -> service.initialize(USER_ID, List.of(1L)));
            verify(userVectorRepository, never()).save(any());
        }

        @Test
        void rejectsDuplicateContents()
        {
            assertThrows(BusinessException.class, () -> service.initialize(USER_ID, List.of(1L, 1L)));
            verifyNoInteractions(contentVectorService);
        }

        @Test
        void rejectsFewerThanOneOrMoreThanTen()
        {
            assertThrows(BusinessException.class, () -> service.initialize(USER_ID, List.of()));
            assertThrows(BusinessException.class, () -> service.initialize(USER_ID,
                    List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L)));
            verifyNoInteractions(contentVectorService);
        }

        /** 가입 화면이 보여준 후보 목록 밖의 콘텐츠는 화면을 거치지 않은 요청이다. Qdrant까지 가지 않고 거절한다. */
        @Test
        void rejectsContentOutsideOnboardingCandidates()
        {
            when(onboardingContentRepository.countByContentIdIn(Set.of(1L, 999L))).thenReturn(1L);

            assertThrows(BusinessException.class, () -> service.initialize(USER_ID, List.of(1L, 999L)));
            verifyNoInteractions(contentVectorService);
            verify(userVectorRepository, never()).save(any());
        }

        /** 일부만으로 평균을 내면 사용자는 고른 대로 반영됐다고 믿는데 실제 벡터는 다르게 만들어진다. */
        @Test
        void rejectsWhenAnySelectedContentHasNoVector()
        {
            when(onboardingContentRepository.countByContentIdIn(Set.of(1L, 999L))).thenReturn(2L);
            when(contentVectorService.findVectors(Set.of(1L, 999L))).thenReturn(Map.of(1L, axis(0)));

            assertThrows(BusinessException.class, () -> service.initialize(USER_ID, List.of(1L, 999L)));
            verify(userVectorRepository, never()).save(any());
        }
    }

    @Nested
    class RecalculateOnLikeChange {

        @Test
        void recomputesFromSelectedContentsWhenLikesDropToZero()
        {
            UserVector stored = stored(axis(5), List.of(1L));
            when(boardFeedbackRepository.findLikedBoardsWithTime(USER_ID, Rating.LIKE)).thenReturn(List.of());
            when(contentVectorService.findVectors(List.of(1L))).thenReturn(Map.of(1L, axis(0)));

            service.recalculateFor(USER_ID);

            assertArrayEquals(axis(0), stored.getUserVector());
            verify(metrics).recordRecalculation(any(), eq(UserVectorMetrics.Result.UPDATED));
        }

        /**
         * 좋아요한 날이 하루면 그 보드의 가중치는 (1-α)^0 = 1, 가입 벡터는 (1-α)^1 = 0.75.
         * 결과는 normalize(1·보드 + 0.75·가입)이다.
         */
        @Test
        void selectedContentsAreWeightedOneRankAfterTheOldestLikeDay()
        {
            UserVector stored = stored(axis(5), List.of(1L));
            likedBoardOf(10L, LocalDateTime.of(2026, 9, 20, 10, 0), 100L,
                    Map.of(100L, axis(1), 1L, axis(0)));

            service.recalculateFor(USER_ID);

            float[] vector = stored.getUserVector();
            double norm = Math.sqrt(1 + 0.75 * 0.75);
            assertEquals(1.0 / norm, vector[1], 1e-6);
            assertEquals(0.75 / norm, vector[0], 1e-6);
            // 고른 콘텐츠는 좋아요 보드와 같은 조회에 실린다
            verify(contentVectorService).findVectors(Set.of(100L, 1L));
        }

        /** 이 마이그레이션 전에 만들어진 행은 고른 기록이 빈 배열이다. 좋아요만으로 계산한다. */
        @Test
        void rowsWithoutSelectedContentsUseLikesOnly()
        {
            UserVector stored = stored(axis(5), List.of());
            likedBoardOf(10L, LocalDateTime.of(2026, 9, 20, 10, 0), 100L, Map.of(100L, axis(1)));

            service.recalculateFor(USER_ID);

            assertArrayEquals(axis(1), stored.getUserVector());
        }

        /** Qdrant 조회가 깨졌을 가능성이 커서, 가입 벡터로 되돌리면 좋아요 이력이 조용히 사라진다. */
        @Test
        void keepsCurrentVectorWhenNoLikedBoardVectorCanBeRead()
        {
            float[] before = axis(5);
            UserVector stored = stored(before, List.of(1L));
            when(boardFeedbackRepository.findLikedBoardsWithTime(USER_ID, Rating.LIKE))
                    .thenReturn(List.of(new LikedBoardDto(10L, LocalDateTime.now())));
            when(boardItemRepository.findContentIdsByBoardIds(List.of(10L)))
                    .thenReturn(List.of(new BoardContentIdDto(10L, 100L)));
            when(contentVectorService.findVectors(any())).thenReturn(Map.of());

            service.recalculateFor(USER_ID);

            assertSame(before, stored.getUserVector());
            verify(metrics).recordRecalculation(any(), eq(UserVectorMetrics.Result.UNCHANGED));
        }
    }

    @Test
    void recommendationUsesStoredVector()
    {
        stored(axis(3), List.of(1L));

        assertArrayEquals(axis(3), service.getVector(USER_ID));
    }

    @Test
    void noVectorBeforeContentsAreSelected()
    {
        when(userVectorRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getVector(USER_ID));
    }

    private UserVector stored(float[] vector, List<Long> initialContentIds)
    {
        UserVector stored = UserVector.builder()
                .userVector(vector)
                .initialContentIds(initialContentIds)
                .updatedAt(LocalDateTime.now())
                .build();
        when(userVectorRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        return stored;
    }

    private void likedBoardOf(Long boardId, LocalDateTime likedAt, Long contentId, Map<Long, float[]> vectors)
    {
        when(boardFeedbackRepository.findLikedBoardsWithTime(USER_ID, Rating.LIKE))
                .thenReturn(List.of(new LikedBoardDto(boardId, likedAt)));
        when(boardItemRepository.findContentIdsByBoardIds(List.of(boardId)))
                .thenReturn(List.of(new BoardContentIdDto(boardId, contentId)));
        when(contentVectorService.findVectors(any())).thenReturn(vectors);
    }

    /** 한 축만 1인 단위 벡터. 결과를 손으로 계산해 비교하기 쉽다. */
    private static float[] axis(int index)
    {
        float[] vector = new float[DIMENSION];
        vector[index] = 1.0f;
        return vector;
    }

    private static double norm(float[] vector)
    {
        double squaredSum = 0;
        for (float value : vector)
        {
            squaredSum += (double) value * value;
        }
        return Math.sqrt(squaredSum);
    }
}
