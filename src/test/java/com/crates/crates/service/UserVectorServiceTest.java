package com.crates.crates.service;

import com.crates.crates.entity.user.UserVector;
import com.crates.crates.enumData.Rating;
import com.crates.crates.repository.BoardFeedbackRepository;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.UserVectorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserVectorServiceTest {

    private static final Long USER_ID = 42L;
    private static final int DIMENSION = 768;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 12, 0);

    @Mock private UserVectorRepository userVectorRepository;
    @Mock private BoardFeedbackRepository boardFeedbackRepository;
    @Mock private BoardItemRepository boardItemRepository;
    @Mock private ContentVectorService contentVectorService;
    @Mock private UserVectorMetrics metrics;

    private UserVectorService service;

    @BeforeEach
    void setUp()
    {
        service = new UserVectorService(userVectorRepository, boardFeedbackRepository,
                boardItemRepository, contentVectorService, metrics);
        ReflectionTestUtils.setField(service, "vectorDimension", DIMENSION);
        ReflectionTestUtils.setField(service, "initialSeed", 20260907L);
    }

    @AfterEach
    void clearTransactionState()
    {
        // ThreadLocal이라 테스트가 중간에 실패해도 다음 테스트로 새지 않게 반드시 되돌린다.
        // (false를 주면 내부적으로 null이 되어 완전히 해제된다.)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @AfterEach
    void doesNotRecalculateLikesOrRecordRecalculationMetrics()
    {
        verify(boardFeedbackRepository, never()).findLikedBoardsWithTime(anyLong(), any());
        verifyNoInteractions(boardItemRepository, contentVectorService, metrics);
    }

    @Test
    void todaysVectorIsReturnedWithoutCheckingLikes()
    {
        UserVector stored = storedVector(NOW.minusHours(1));
        float[] original = stored.getUserVector();
        LocalDateTime updatedAt = stored.getUpdatedAt();

        try (MockedStatic<LocalDateTime> time = mockStatic(LocalDateTime.class))
        {
            time.when(LocalDateTime::now).thenReturn(NOW);

            assertSame(original, service.resolveVector(USER_ID));
            assertEquals(updatedAt, stored.getUpdatedAt());
        }

        verifyNoInteractions(boardFeedbackRepository);
    }

    @Test
    void oldVectorWithLikesIsPreserved()
    {
        UserVector stored = storedVector(NOW.minusDays(1));
        float[] original = stored.getUserVector();
        float[] originalValues = original.clone();
        LocalDateTime updatedAt = stored.getUpdatedAt();
        when(boardFeedbackRepository.existsByUserIdAndRating(USER_ID, Rating.LIKE)).thenReturn(true);

        try (MockedStatic<LocalDateTime> time = mockStatic(LocalDateTime.class))
        {
            time.when(LocalDateTime::now).thenReturn(NOW);

            assertSame(original, service.resolveVector(USER_ID));
            assertArrayEquals(originalValues, stored.getUserVector());
            assertEquals(updatedAt, stored.getUpdatedAt());
        }

        verify(boardFeedbackRepository).existsByUserIdAndRating(USER_ID, Rating.LIKE);
    }

    @Test
    void coldStartVectorRefreshesOncePerDay()
    {
        UserVector stored = storedVector(NOW.minusDays(1));
        float[] original = stored.getUserVector().clone();
        LocalDateTime laterToday = NOW.plusHours(1);
        LocalDateTime tomorrow = NOW.plusDays(1);
        when(boardFeedbackRepository.existsByUserIdAndRating(USER_ID, Rating.LIKE)).thenReturn(false);

        try (MockedStatic<LocalDateTime> time = mockStatic(LocalDateTime.class))
        {
            time.when(LocalDateTime::now).thenReturn(NOW);
            float[] fresh = service.resolveVector(USER_ID);

            assertSame(fresh, stored.getUserVector());
            assertFalse(Arrays.equals(original, fresh));
            assertEquals(DIMENSION, fresh.length);
            assertEquals(1.0, norm(fresh), 1e-6);
            assertEquals(NOW, stored.getUpdatedAt());

            time.when(LocalDateTime::now).thenReturn(laterToday);
            assertSame(fresh, service.resolveVector(USER_ID));
            assertEquals(NOW, stored.getUpdatedAt());
            verify(boardFeedbackRepository, times(1)).existsByUserIdAndRating(USER_ID, Rating.LIKE);

            time.when(LocalDateTime::now).thenReturn(tomorrow);
            float[] nextDay = service.resolveVector(USER_ID);
            assertFalse(Arrays.equals(fresh, nextDay));
            assertEquals(tomorrow, stored.getUpdatedAt());
            assertSame(nextDay, stored.getUserVector());
        }

        verify(boardFeedbackRepository, times(2)).existsByUserIdAndRating(USER_ID, Rating.LIKE);
    }

    /**
     * 읽기 전용 트랜잭션에 합류하면 Hibernate가 플러시를 막아 저장이 조용히 사라진다.
     * 그 상황을 예외로 바꾸는 가드가 살아 있는지 고정한다.
     */
    @Test
    void refreshInsideReadOnlyTransactionFailsLoudlyInsteadOfSilentlySkippingTheSave()
    {
        UserVector stored = storedVector(NOW.minusDays(1));
        float[] originalValues = stored.getUserVector().clone();
        LocalDateTime updatedAt = stored.getUpdatedAt();
        when(boardFeedbackRepository.existsByUserIdAndRating(USER_ID, Rating.LIKE)).thenReturn(false);

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try (MockedStatic<LocalDateTime> time = mockStatic(LocalDateTime.class))
        {
            time.when(LocalDateTime::now).thenReturn(NOW);

            assertThrows(IllegalStateException.class, () -> service.resolveVector(USER_ID));
            // 예외만 던지고 끝나는 게 아니라, 기존 값이 훼손되지 않은 채 남아야 한다.
            assertArrayEquals(originalValues, stored.getUserVector());
            assertEquals(updatedAt, stored.getUpdatedAt());
        }
    }

    /**
     * 가드는 "쓰기가 필요한 지점"에만 있어야 한다. 갱신할 것이 없으면 읽기 전용이어도
     * 정상 동작이므로, 여기서 예외가 나면 멀쩡한 요청까지 막는 과잉 방어다.
     */
    @Test
    void readOnlyTransactionIsFineWhenNoRefreshIsNeeded()
    {
        UserVector stored = storedVector(NOW.minusHours(1));
        float[] original = stored.getUserVector();

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try (MockedStatic<LocalDateTime> time = mockStatic(LocalDateTime.class))
        {
            time.when(LocalDateTime::now).thenReturn(NOW);

            assertSame(original, service.resolveVector(USER_ID));
        }
    }

    @Test
    void getVectorRemainsAReadOnlyLookupForOldVectors()
    {
        UserVector stored = storedVector(NOW.minusDays(1));
        float[] original = stored.getUserVector();
        LocalDateTime updatedAt = stored.getUpdatedAt();

        assertSame(original, service.getVector(USER_ID));
        assertEquals(updatedAt, stored.getUpdatedAt());
        verifyNoInteractions(boardFeedbackRepository);
    }

    private UserVector storedVector(LocalDateTime updatedAt)
    {
        float[] vector = new float[DIMENSION];
        vector[0] = 1.0f;
        UserVector stored = UserVector.builder()
                .userVector(vector)
                .updatedAt(updatedAt)
                .build();
        when(userVectorRepository.findById(USER_ID)).thenReturn(Optional.of(stored));
        return stored;
    }

    private double norm(float[] vector)
    {
        double squaredSum = 0;
        for (float value : vector)
        {
            squaredSum += (double) value * value;
        }
        return Math.sqrt(squaredSum);
    }
}
