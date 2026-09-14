package com.crates.crates.service;

import com.crates.crates.DTO.BoardContentIdDto;
import com.crates.crates.DTO.LikedBoardDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.ai.DeterministicVectorFactory;
import com.crates.crates.entity.user.User;
import com.crates.crates.entity.user.UserVector;
import com.crates.crates.enumData.Rating;
import com.crates.crates.repository.BoardFeedbackRepository;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.UserVectorRepository;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 사용자 취향 벡터.
 *
 * <p>좋아요한 보드들의 콘텐츠 벡터에서 <b>매번 다시 계산</b>한다. 누적 갱신(증분)을 쓰지 않는
 * 이유는 좋아요 취소 때문이다. 증분 방식에서는 취소된 항의 기여를 되돌리는 역연산이 필요한데,
 * float32에서 더하고 빼기를 반복하면 오차가 쌓이고 마지막 좋아요를 취소하면 0으로 나누게 된다.
 * 재계산은 그 항을 빼고 다시 평균 내면 끝이라 취소가 특별한 경우가 아니게 된다.</p>
 *
 * <p>"첫 좋아요인지" 판별하는 로직도 필요 없다. 초기 랜덤 벡터는 애초에 평균에 들어가지 않고,
 * 좋아요 집합이 비었을 때만 쓰인다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserVectorService {

    /** 이보다 크기가 작으면 방향이 없다고 보고 정규화를 건너뛴다. */
    private static final double NORMALIZE_EPSILON = 1e-6;

    /**
     * 시작 벡터 seed에서 사용자 id와 날짜를 갈라 담는 자리수.
     * epochDay보다 커야 두 값이 섞이지 않는다. 100만이면 서기 4707년까지 안전하다.
     */
    private static final long EPOCH_DAY_SLOT = 1_000_000L;

    private final UserVectorRepository userVectorRepository;
    private final BoardFeedbackRepository boardFeedbackRepository;
    private final BoardItemRepository boardItemRepository;
    private final ContentVectorService contentVectorService;
    private final UserVectorMetrics metrics;

    @Value("${ai.server.embedding-dimension}")
    private int vectorDimension;

    /**
     * 최근 활동일에 얼마나 더 무게를 둘지. 클수록 최근 취향이 빨리 지배한다.
     * 0.25면 최근 10번째 활동일의 가중치가 0.75^10 ≈ 0.075로, 사실상 최근 10회 방문이 취향을 정한다.
     */
    @Value("${ai.user-vector.decay-alpha}")
    private double decayAlpha;

    /** 시작 벡터 seed. 사용자 id와 날짜를 함께 섞어, 같은 사용자·같은 날에는 항상 같은 벡터가 나온다. */
    @Value("${ai.user-vector.initial-seed}")
    private long initialSeed;

    public float[] getVector(Long userId)
    {
        return userVectorRepository.findById(userId)
                .map(UserVector::getUserVector)
                .orElseThrow(() -> new BusinessException("사용자 벡터가 존재하지 않습니다. userId: " + userId));
    }

    /**
     * 가입 시점에 L2 정규화된 랜덤 벡터를 넣어둔다.
     *
     * <p>0 벡터를 쓸 수 없다. Cosine 거리는 0 벡터에 대해 정의되지 않아(norm이 0이라 정규화 불가)
     * 추천 자체가 불가능해진다. 랜덤 벡터는 어떤 콘텐츠와도 특별히 가깝지 않아 사실상 임의 추천이
     * 되지만, 적어도 화면이 채워지고 첫 좋아요를 받는 순간 실제 취향으로 대체된다.</p>
     */
    @Transactional
    public void initializeFor(User user)
    {
        userVectorRepository.save(UserVector.builder()
                .user(user)
                .userVector(initialVectorOf(user.getId(), LocalDate.now()))
                .updatedAt(LocalDateTime.now())
                .build());
    }

    /**
     * 좋아요 집합 전체로부터 취향 벡터를 다시 만든다.
     *
     * <p>가중치는 <b>좋아요한 날짜의 최신순 순위</b>로 정한다. 같은 날 누른 것은 같은 가중치를
     * 받으므로, 한 세션에 여러 개를 몰아 눌러도 앞의 것이 밀려나지 않는다. 실제 달력 거리를 쓰지
     * 않는 이유는, 오랜만에 돌아온 사용자의 과거 취향이 통째로 사라지지 않게 하기 위해서다.</p>
     *
     * <pre>
     *   V = normalize( Σ wᵢ · normalize(보드ᵢ의 콘텐츠 평균) )
     *   wᵢ = (1-α)^(그 보드를 좋아요한 날짜의 최신순 순위)
     * </pre>
     *
     * <p>정규화가 두 번 나오는데 역할이 다르다. <b>안쪽</b>은 보드 하나가 한 표가 되게 해서
     * 가중치를 날짜 순위만으로 통제한다(boardVectorOf 참고). <b>바깥쪽</b>은 순위에 영향이 없고
     * (Qdrant가 질의 벡터를 정규화한다) 저장값의 크기를 1로 고정해 로그와 모니터링에서
     * 이상값을 알아보기 쉽게 하려는 것이다.</p>
     */
    @Transactional
    public void recalculateFor(Long userId)
    {
        Timer.Sample sample = metrics.startRecalculation();

        List<LikedBoardDto> liked = boardFeedbackRepository.findLikedBoardsWithTime(userId, Rating.LIKE);
        // 비용이 이 값에 비례하므로 소요 시간과 함께 봐야 해석이 된다.
        metrics.recordLikedBoards(liked.size());

        UserVector stored = userVectorRepository.findById(userId).orElse(null);
        if (stored == null)
        {
            // 가입 시 만들어지므로 정상 흐름에서는 오지 않는다. 없으면 계산할 근거도 없다.
            log.warn("취향 벡터 행이 없어 재계산을 건너뜁니다. userId: {}", userId);
            metrics.recordRecalculation(sample, UserVectorMetrics.Result.SKIPPED);
            return;
        }

        float[] recalculated = liked.isEmpty()
                // 좋아요를 전부 취소하면 시작 벡터로 되돌린다. 날짜가 섞여 있어 가입 시점과 같지 않다.
                ? initialVectorOf(userId, LocalDate.now())
                : weightedAverageOf(liked);

        if (recalculated == null)
        {
            log.warn("좋아요한 보드의 콘텐츠 벡터를 찾지 못해 취향 벡터를 유지합니다. userId: {}", userId);
            metrics.recordRecalculation(sample, UserVectorMetrics.Result.UNCHANGED);
            return;
        }

        stored.updateVector(recalculated, LocalDateTime.now());
        metrics.recordRecalculation(sample, UserVectorMetrics.Result.UPDATED);
    }

    /**
     * 좋아요한 보드들의 가중 평균. 쓸 수 있는 벡터가 하나도 없으면 null.
     *
     * <p>보드 평균을 <b>정규화한 뒤</b> 가중치를 곱한다. 순서가 중요하다. 정규화를 나중에 하면
     * {@code normalize(w·b) == normalize(b)}가 되어 가중치가 통째로 사라진다.</p>
     */
    private float[] weightedAverageOf(List<LikedBoardDto> liked)
    {
        Map<Long, Double> weightByBoardId = weightByBoardId(liked);

        List<BoardContentIdDto> pairs =
                boardItemRepository.findContentIdsByBoardIds(List.copyOf(weightByBoardId.keySet()));

        // 보드 수와 무관하게 Qdrant 왕복은 한 번이다.
        Set<Long> contentIds = pairs.stream()
                .map(BoardContentIdDto::contentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, float[]> vectorByContentId = contentVectorService.findVectors(contentIds);

        Map<Long, List<Long>> contentIdsByBoardId = pairs.stream()
                .collect(Collectors.groupingBy(
                        BoardContentIdDto::boardId,
                        Collectors.mapping(BoardContentIdDto::contentId, Collectors.toList())
                ));

        float[] accumulated = new float[vectorDimension];
        boolean anyBoardCounted = false;

        for (Map.Entry<Long, Double> entry : weightByBoardId.entrySet())
        {
            float[] boardVector = boardVectorOf(contentIdsByBoardId.get(entry.getKey()), vectorByContentId);
            if (boardVector == null)
            {
                // Qdrant에 벡터가 아직 없는 콘텐츠만으로 이뤄진 보드. 평균에서 뺀다.
                continue;
            }

            double weight = entry.getValue();
            for (int index = 0; index < vectorDimension; index++)
            {
                accumulated[index] += (float) (boardVector[index] * weight);
            }
            anyBoardCounted = true;
        }

        if (!anyBoardCounted)
        {
            // 어느 보드도 벡터를 만들지 못했다. 조회 자체가 안 되는 것인지(0건) 일부만 비어 있는지를
            // 숫자로 남긴다. 호출부의 경고만으로는 둘을 구분할 수 없어 원인 추적이 막힌다.
            log.error("좋아요한 보드 {}건에서 콘텐츠 {}건을 조회했으나 쓸 수 있는 벡터가 {}건입니다.",
                    weightByBoardId.size(), contentIds.size(), vectorByContentId.size());
            return null;
        }

        // 가중치 합으로 나누지 않는다. 어차피 정규화하므로 양수 스칼라로 나누는 것은 결과에 영향이 없다.
        return normalized(accumulated);
    }

    /**
     * 보드마다 가중치를 매긴다.
     *
     * <p>좋아요한 날짜를 최신순으로 줄 세워 0부터 순위를 매기고 (1-α)^순위를 준다.
     * 같은 날 누른 보드는 같은 순위를 공유한다.</p>
     */
    private Map<Long, Double> weightByBoardId(List<LikedBoardDto> liked)
    {
        List<LocalDate> datesNewestFirst = liked.stream()
                .map(item -> item.likedAt().toLocalDate())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();

        Map<LocalDate, Double> weightByDate = new HashMap<>(datesNewestFirst.size());
        for (int rank = 0; rank < datesNewestFirst.size(); rank++)
        {
            weightByDate.put(datesNewestFirst.get(rank), Math.pow(1.0 - decayAlpha, rank));
        }

        Map<Long, Double> weightByBoardId = new HashMap<>(liked.size());
        for (LikedBoardDto item : liked)
        {
            // 같은 보드가 두 번 나올 수 없다(board_feedback의 unique 제약).
            weightByBoardId.put(item.boardId(), weightByDate.get(item.likedAt().toLocalDate()));
        }
        return weightByBoardId;
    }

    /**
     * 보드 하나를 대표하는 벡터. 콘텐츠 8건의 평균을 L2 정규화한 값이다. 쓸 수 있는 콘텐츠가 없으면 null.
     *
     * <p>정규화하는 이유는 <b>보드 하나가 한 표가 되게</b> 하기 위해서다. 정규화하지 않으면 평균 벡터의
     * 크기가 그대로 가중치에 곱해지는데, 그 크기는 8건이 서로 얼마나 비슷한지(응집도)를 나타낸다.
     * 주제가 뚜렷한 보드는 크기가 0.96까지, 잡다한 보드는 0.35까지 나와 <b>최대 2.7배</b> 차이가 난다.
     * 우리가 설계한 가중치는 날짜 순위 하나뿐인데 의도하지 않은 요소가 끼어드는 셈이다.</p>
     *
     * <p>사용자가 하는 행동은 "보드에 좋아요"지 "콘텐츠에 좋아요"가 아니다. 세는 단위를 보드로
     * 맞춘다. (정규화하지 않으면 콘텐츠 하나하나를 한 표로 세는 것과 수학적으로 같아진다.)</p>
     */
    private float[] boardVectorOf(List<Long> contentIds, Map<Long, float[]> vectorByContentId)
    {
        if (contentIds == null || contentIds.isEmpty())
        {
            return null;
        }

        float[] sum = new float[vectorDimension];
        int counted = 0;
        int mismatched = 0;
        int sampleLength = 0;
        Long sampleContentId = null;

        for (Long contentId : contentIds)
        {
            float[] vector = vectorByContentId.get(contentId);
            if (vector == null)
            {
                // Qdrant에 아직 벡터가 없는 콘텐츠. 정상적으로 생길 수 있는 상황이라 조용히 건너뛴다.
                continue;
            }
            if (vector.length != vectorDimension)
            {
                mismatched++;
                sampleLength = vector.length;
                sampleContentId = contentId;
                continue;
            }
            for (int index = 0; index < vectorDimension; index++)
            {
                sum[index] += vector[index];
            }
            counted++;
        }

        // 길이가 다른 것은 "아직 벡터가 없다"와 성격이 다르다. 설정이 어긋났거나(차원 불일치)
        // Qdrant 응답을 읽지 못하고 있다는 뜻이라, 없는 것과 똑같이 건너뛰면 원인이 묻힌다.
        // 실제로 클라이언트-서버 버전이 어긋나 빈 배열(length 0)이 돌아온 적이 있고,
        // 그때 남은 단서가 아래 recalculateFor의 경고 한 줄뿐이라 원인을 찾기 어려웠다.
        if (mismatched > 0)
        {
            log.error("콘텐츠 벡터의 차원이 기대와 다릅니다. {}건 / 기대: {} / 실제: {} (예: contentId {}). "
                            + "실제가 0이면 Qdrant 응답을 파싱하지 못한 것이니 서버 이미지 태그와 "
                            + "io.qdrant:client 버전이 맞는지 확인하세요.",
                    mismatched, vectorDimension, sampleLength, sampleContentId);
        }

        if (counted == 0)
        {
            return null;
        }

        // 평균을 낸 뒤 정규화한다. 정규화가 크기를 없애므로 counted로 나누는 것은 사실 생략해도
        // 결과가 같지만, "콘텐츠 평균"이라는 의미를 코드에 남겨둔다.
        for (int index = 0; index < vectorDimension; index++)
        {
            sum[index] = sum[index] / counted;
        }
        return normalized(sum);
    }

    /**
     * L2 정규화.
     *
     * <p>크기가 0에 가까우면 원본을 그대로 돌려준다. 방향이 없는 벡터를 정규화하면 부동소수점
     * 잔차가 증폭돼 의미 없는 방향이 나온다. 768차원 실제 임베딩에서 벡터들이 정확히 상쇄될 일은
     * 없으므로 방어용 가드다.</p>
     */
    private static float[] normalized(float[] vector)
    {
        double squaredSum = 0.0;
        for (float value : vector)
        {
            squaredSum += (double) value * value;
        }

        double norm = Math.sqrt(squaredSum);
        if (norm < NORMALIZE_EPSILON)
        {
            return vector;
        }

        float[] result = new float[vector.length];
        for (int index = 0; index < vector.length; index++)
        {
            result[index] = (float) (vector[index] / norm);
        }
        return result;
    }

    /**
     * 좋아요 정보가 없을 때 쓰는 시작 벡터.
     *
     * <p>seed에 <b>날짜</b>를 섞는다. 사용자 id만 쓰면 가입 시점과 좋아요를 전부 취소한 시점의
     * 벡터가 같아지고, 좋아요가 없는 사용자는 "오늘의 추천 보드"에서 매일 같은 4개를 보게 된다.
     * 날짜가 들어가면 하루마다 다른 시작점을 받는다.</p>
     *
     * <p>시각이 아니라 날짜인 것이 중요하다. 재계산은 비동기라 연타하면 여러 번 돌 수 있는데,
     * 초 단위로 섞으면 호출마다 다른 값이 나와 "재계산은 언제 돌려도 같은 결과"라는 성질이 깨진다.
     * 날짜 단위면 하루 안에서는 멱등하다.</p>
     */
    private float[] initialVectorOf(long userId, LocalDate on)
    {
        // (사용자, 날짜)를 자리수로 갈라 담아 서로 다른 쌍이 절대 같은 seed가 되지 않게 한다.
        // 해시를 한 번 더 씌우지 않는 이유는 SplittableRandom이 내부에서 이미 seed를 섞기 때문이다
        // (nextLong = mix64(seed + gamma)). seed가 1만 달라도 결과 벡터는 서로 무관하다.
        long seed = initialSeed + userId * EPOCH_DAY_SLOT + on.toEpochDay();

        return DeterministicVectorFactory.create(seed, vectorDimension);
    }
}
