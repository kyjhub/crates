package com.crates.crates.service;

import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.user.User;
import com.crates.crates.entity.user.UserVector;
import com.crates.crates.repository.UserVectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserVectorService {

    private final UserVectorRepository userVectorRepository;

    @Value("${ai.server.embedding-dimension}")
    private int vectorDimension;

    public float[] getVector(Long userId)
    {
        return userVectorRepository.findById(userId)
                .map(UserVector::getUserVector)
                .orElseThrow(() -> new BusinessException("사용자 벡터가 존재하지 않습니다. userId: " + userId));
    }

    /**
     * 가입 시점에 취향 벡터 자리를 0으로 채워 만들어둔다.
     *
     * <p>값은 나중에 AI 서버의 배치가 UPDATE한다. 행을 미리 만들어두면 배치가 INSERT/UPDATE를
     * 구분할 필요가 없고, 조회하는 쪽도 "행이 아직 없는 경우"를 따로 다루지 않아도 된다.</p>
     *
     * <p>0 벡터는 <b>"아직 취향이 계산되지 않았다"</b>는 표시값이다. Cosine 거리는 0 벡터에 대해
     * 정의되지 않으므로(‖0‖ = 0이라 정규화할 수 없다) 이 값을 그대로 Qdrant에 넣으면 안 된다.
     * {@link #isUninitialized(float[])}로 걸러낸다.</p>
     */
    @Transactional
    public void initializeFor(User user)
    {
        userVectorRepository.save(UserVector.builder()
                .user(user)
                // Java의 float[]는 0.0f로 초기화된다.
                .userVector(new float[vectorDimension])
                .updatedAt(LocalDateTime.now())
                .build());
    }

    /** AI 배치가 아직 값을 채우지 않은 상태인지. */
    public static boolean isUninitialized(float[] userVector)
    {
        for (float value : userVector)
        {
            if (value != 0.0f)
            {
                return false;
            }
        }
        return true;
    }
}
