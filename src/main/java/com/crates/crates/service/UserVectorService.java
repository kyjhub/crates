package com.crates.crates.service;

import com.crates.crates.DTO.BoardContentIdDto;
import com.crates.crates.DTO.LikedBoardDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.user.UserVector;
import com.crates.crates.enumData.Rating;
import com.crates.crates.repository.BoardFeedbackRepository;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.OnboardingContentRepository;
import com.crates.crates.repository.UserRepository;
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
 * <p><b>시작점</b> — 가입 직후 사용자가 콘텐츠를 1~10개 고르면 그 평균이 취향 벡터가 된다
 * ({@link #initialize}). 고른 콘텐츠 id도 함께 저장한다. 고르기 전에는 벡터가 없고, 추천도 받을 수 없다.</p>
 *
 * <p><b>좋아요</b> — 좋아요가 바뀌면 좋아요한 보드들과 가입 때 고른 콘텐츠로 <b>매번 처음부터 다시 계산</b>한다
 * ({@link #recalculateFor}). 재료가 모두 DB(좋아요, 고른 id)와 Qdrant(콘텐츠 벡터)에 있어서 저장된 벡터는
 * 언제든 다시 만들 수 있는 값이다. 누적 갱신(증분)을 쓰지 않는 이유는 좋아요 취소 때문이다. 증분 방식에서는
 * 취소된 항의 기여를 되돌리는 역연산이 필요한데, float32에서 더하고 빼기를 반복하면 오차가 쌓인다.
 * 재계산은 그 항을 빼고 다시 평균 내면 끝이라 취소가 특별한 경우가 아니게 된다.</p>
 *
 * <p>좋아요가 0개가 되면 가입 때 고른 콘텐츠만으로 다시 계산한다. 콘텐츠 벡터가 그대로면 가입 직후와 같은 값이다.
 * 가입 벡터(평균값) 자체를 저장하지 않는 이유는 콘텐츠 벡터가 새 모델로 바뀌었을 때 옛 모델 값이 남아
 * 좋아요 쪽과 섞이지 않게 하기 위해서다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserVectorService {

    /** 이보다 크기가 작으면 방향이 없다고 보고 정규화를 건너뛴다. */
    private static final double NORMALIZE_EPSILON = 1e-6;

    /** 가입 때 고를 수 있는 콘텐츠 수. 요청 DTO도 같은 범위를 검증한다. */
    public static final int MIN_INITIAL_CONTENTS = 1;
    public static final int MAX_INITIAL_CONTENTS = 10;

    private final UserVectorRepository userVectorRepository;
    private final UserRepository userRepository;
    private final BoardFeedbackRepository boardFeedbackRepository;
    private final BoardItemRepository boardItemRepository;
    private final ContentVectorService contentVectorService;
    private final OnboardingContentRepository onboardingContentRepository;
    private final UserVectorMetrics metrics;

    @Value("${ai.server.embedding-dimension}")
    private int vectorDimension;

    /**
     * 최근 활동일에 얼마나 더 무게를 둘지. 클수록 최근 취향이 빨리 지배한다.
     * 0.25면 최근 10번째 활동일의 가중치가 0.75^10 ≈ 0.075로, 사실상 최근 10회 방문이 취향을 정한다.
     */
    @Value("${ai.user-vector.decay-alpha}")
    private double decayAlpha;

    /** 추천에 쓸 벡터. 좋아요가 반영된 현재 값이다. */
    public float[] getVector(Long userId)
    {
        return userVectorRepository.findById(userId)
                .map(UserVector::getUserVector)
                .orElseThrow(() -> new BusinessException("취향 콘텐츠를 먼저 선택해주세요."));
    }

    /** 가입 때 콘텐츠를 골랐는지. 고르기 전에는 행이 없다. */
    public boolean hasVector(Long userId)
    {
        return userVectorRepository.existsById(userId);
    }

    /**
     * 가입 때 고른 콘텐츠의 평균으로 취향 벡터를 만들고, 고른 id를 함께 저장한다. 사용자마다 한 번만 할 수 있다.
     *
     * <p>콘텐츠마다 벡터를 L2 정규화된 채로 더해 평균 낸 뒤 다시 정규화한다. 보드 하나의 대표 벡터를
     * 만드는 방식({@link #averageOf})과 같아서, 고른 콘텐츠는 "사용자가 직접 만든 보드 한 장"처럼 취급된다.</p>
     *
     * <p>가입 화면이 보여준 후보 목록(onboarding_content, 종류별 인기순 200개) 밖의 콘텐츠는 받지 않는다.</p>
     *
     * <p>고른 콘텐츠 중 하나라도 벡터가 없으면 거절한다. 일부만으로 평균을 내면 사용자는 고른 대로
     * 반영됐다고 믿는데 실제 벡터는 다르게 만들어진다. 후보는 벡터가 있는 콘텐츠로만 채우므로 정상이라면
     * Qdrant가 비어 있을 때(재적재 중)만 여기에 걸린다.</p>
     */
    @Transactional
    public void initialize(Long userId, List<Long> contentIds)
    {
        if (userVectorRepository.existsById(userId))
        {
            throw new BusinessException("이미 취향 콘텐츠를 선택했습니다.");
        }

        Set<Long> distinctIds = new LinkedHashSet<>(contentIds);
        if (distinctIds.size() != contentIds.size())
        {
            throw new BusinessException("같은 콘텐츠를 두 번 고를 수 없습니다.");
        }
        if (distinctIds.size() < MIN_INITIAL_CONTENTS || distinctIds.size() > MAX_INITIAL_CONTENTS)
        {
            throw new BusinessException("콘텐츠는 " + MIN_INITIAL_CONTENTS + "개 이상 "
                    + MAX_INITIAL_CONTENTS + "개 이하로 골라주세요.");
        }

        // 가입 화면이 보여준 후보 목록(onboarding_content) 안에서만 고를 수 있다. 목록 밖의 id는 화면을
        // 거치지 않은 요청이므로 받지 않는다. Qdrant 왕복 전에 DB에서 먼저 거른다.
        if (onboardingContentRepository.countByContentIdIn(distinctIds) != distinctIds.size())
        {
            throw new BusinessException("선택할 수 없는 콘텐츠가 포함돼 있습니다.");
        }

        Map<Long, float[]> vectorByContentId = contentVectorService.findVectors(distinctIds);
        if (vectorByContentId.size() != distinctIds.size())
        {
            throw new BusinessException("선택할 수 없는 콘텐츠가 포함돼 있습니다.");
        }

        List<Long> initialContentIds = List.copyOf(distinctIds);
        float[] initialVector = averageOf(initialContentIds, vectorByContentId);
        if (initialVector == null)
        {
            // 벡터는 받았는데 차원이 전부 어긋났다. averageOf가 원인을 로그로 남긴다.
            throw new IllegalStateException("콘텐츠 벡터로 취향 벡터를 만들지 못했습니다. userId: " + userId);
        }

        userVectorRepository.save(UserVector.builder()
                .user(userRepository.getReferenceById(userId))
                .userVector(initialVector)
                .initialContentIds(initialContentIds)
                .updatedAt(LocalDateTime.now())
                .build());
    }

    /**
     * 좋아요 집합 전체와 가입 때 고른 콘텐츠로 취향 벡터를 다시 만든다. 좋아요가 0개면 고른 콘텐츠만으로 만든다.
     *
     * <p>가중치는 <b>좋아요한 날짜의 최신순 순위</b>로 정한다. 같은 날 누른 것은 같은 가중치를
     * 받으므로, 한 세션에 여러 개를 몰아 눌러도 앞의 것이 밀려나지 않는다. 실제 달력 거리를 쓰지
     * 않는 이유는, 오랜만에 돌아온 사용자의 과거 취향이 통째로 사라지지 않게 하기 위해서다.</p>
     *
     * <p>고른 콘텐츠의 평균(가입 벡터)은 <b>가장 오래된 좋아요 날짜 다음 순위</b>의 한 표로 들어간다. 가입 때 고른 것이
     * 어떤 좋아요보다도 먼저 한 선택이기 때문이다. 좋아요 이력이 쌓일수록 영향이 자연히 줄어든다
     * (좋아요한 날이 3일이면 (1-α)^3, 10일이면 (1-α)^10).</p>
     *
     * <pre>
     *   V = normalize( Σ wᵢ · normalize(보드ᵢ의 콘텐츠 평균) + w₀ · normalize(고른 콘텐츠 평균) )
     *   wᵢ = (1-α)^(그 보드를 좋아요한 날짜의 최신순 순위)
     *   w₀ = (1-α)^(좋아요한 날짜 수)
     * </pre>
     *
     * <p>정규화가 두 번 나오는데 역할이 다르다. <b>안쪽</b>은 보드 하나가 한 표가 되게 해서
     * 가중치를 날짜 순위만으로 통제한다(averageOf 참고). <b>바깥쪽</b>은 순위에 영향이 없고
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
            // 가입 때 콘텐츠를 골라야 행이 생긴다. 고르기 전에는 좋아요를 누를 화면에 갈 수 없어
            // 정상 흐름에서는 오지 않는다. 없으면 계산할 근거도 없다.
            log.warn("취향 벡터 행이 없어 재계산을 건너뜁니다. userId: {}", userId);
            metrics.recordRecalculation(sample, UserVectorMetrics.Result.SKIPPED);
            return;
        }

        List<Long> initialContentIds = stored.getInitialContentIds();
        float[] recalculated = liked.isEmpty()
                // 좋아요를 전부 취소하면 가입 때 고른 콘텐츠만으로 다시 만든다.
                ? averageOf(initialContentIds, contentVectorService.findVectors(initialContentIds))
                : weightedAverageOf(liked, initialContentIds);
        if (recalculated == null)
        {
            log.warn("콘텐츠 벡터를 찾지 못해 취향 벡터를 유지합니다. userId: {}, 좋아요 {}건", userId, liked.size());
            metrics.recordRecalculation(sample, UserVectorMetrics.Result.UNCHANGED);
            return;
        }

        stored.updateVector(recalculated, LocalDateTime.now());
        metrics.recordRecalculation(sample, UserVectorMetrics.Result.UPDATED);
    }

    /**
     * 좋아요한 보드들과 가입 때 고른 콘텐츠의 가중 평균. 좋아요한 보드 중 쓸 수 있는 벡터가 하나도 없으면 null.
     *
     * <p>보드 평균을 <b>정규화한 뒤</b> 가중치를 곱한다. 순서가 중요하다. 정규화를 나중에 하면
     * {@code normalize(w·b) == normalize(b)}가 되어 가중치가 통째로 사라진다. 고른 콘텐츠도 같은 방식으로
     * 한 묶음의 대표 벡터를 만든 뒤 가중치를 곱한다.</p>
     *
     * <p>보드가 하나도 계산되지 않으면 고른 콘텐츠만으로 결과를 내지 않고 null을 돌려준다. 그 상황은
     * Qdrant 조회가 깨졌다는 뜻일 가능성이 커서, 고른 콘텐츠만으로 덮어쓰면 좋아요 이력이 조용히 사라진다.
     * 호출부는 기존 값을 유지한다.</p>
     */
    private float[] weightedAverageOf(List<LikedBoardDto> liked, List<Long> initialContentIds)
    {
        Map<Long, Double> weightByBoardId = weightByBoardId(liked);

        List<BoardContentIdDto> pairs =
                boardItemRepository.findContentIdsByBoardIds(List.copyOf(weightByBoardId.keySet()));

        // 보드마다 조회하지 않고 콘텐츠 id를 전부 모아 한 번에 넘긴다. 왕복 횟수는 보드 수가
        // 아니라 콘텐츠 수에 비례하며, gRPC 수신 한도 때문에 1,000개마다 한 번씩 나간다
        // (QdrantPointOperations.RETRIEVE_CHUNK_SIZE). 보드 125개까지는 왕복 한 번이다.
        // 고른 콘텐츠도 같은 조회에 싣는다. 최대 10건이라 왕복 수는 늘지 않는다.
        Set<Long> contentIds = pairs.stream()
                .map(BoardContentIdDto::contentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        contentIds.addAll(initialContentIds);
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
            float[] boardVector = averageOf(contentIdsByBoardId.get(entry.getKey()), vectorByContentId);
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

        // 고른 콘텐츠는 가장 오래된 좋아요 날짜 다음 순위다. 고른 기록이 없는 행(빈 배열)은 좋아요만으로 계산한다.
        float[] initialVector = averageOf(initialContentIds, vectorByContentId);
        if (initialVector != null)
        {
            long likedDays = liked.stream().map(item -> item.likedAt().toLocalDate()).distinct().count();
            double initialWeight = Math.pow(1.0 - decayAlpha, likedDays);
            for (int index = 0; index < vectorDimension; index++)
            {
                accumulated[index] += (float) (initialVector[index] * initialWeight);
            }
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
     * 콘텐츠 묶음을 대표하는 벡터. 콘텐츠 벡터의 평균을 L2 정규화한 값이다. 쓸 수 있는 콘텐츠가 없으면 null.
     * 좋아요한 보드 하나(콘텐츠 8건)와 가입 때 고른 콘텐츠(1~10건)에 같이 쓴다.
     *
     * <p>정규화하는 이유는 <b>보드 하나가 한 표가 되게</b> 하기 위해서다. 정규화하지 않으면 평균 벡터의
     * 크기가 그대로 가중치에 곱해지는데, 그 크기는 8건이 서로 얼마나 비슷한지(응집도)를 나타낸다.
     * 주제가 뚜렷한 보드는 크기가 0.96까지, 잡다한 보드는 0.35까지 나와 <b>최대 2.7배</b> 차이가 난다.
     * 우리가 설계한 가중치는 날짜 순위 하나뿐인데 의도하지 않은 요소가 끼어드는 셈이다.</p>
     *
     * <p>사용자가 하는 행동은 "보드에 좋아요"지 "콘텐츠에 좋아요"가 아니다. 세는 단위를 보드로
     * 맞춘다. (정규화하지 않으면 콘텐츠 하나하나를 한 표로 세는 것과 수학적으로 같아진다.)</p>
     */
    private float[] averageOf(List<Long> contentIds, Map<Long, float[]> vectorByContentId)
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
}
