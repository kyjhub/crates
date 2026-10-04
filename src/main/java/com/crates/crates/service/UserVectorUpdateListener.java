package com.crates.crates.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 좋아요 변화에 맞춰 취향 벡터 재계산을 요청한다.
 *
 * <p><b>AFTER_COMMIT인 이유:</b> 커밋 전에 재계산이 시작되면 별도 커넥션이라 아직 커밋되지 않은 board_feedback 행이
 * 보이지 않고, 방금 누른 좋아요가 빠진 채로 벡터가 계산된다. 에러 없이 한 박자 늦은 값이 저장되는 형태라
 * 원인을 찾기 어렵다.</p>
 *
 * <p>재계산 자체는 여기서 돌리지 않고 {@link UserVectorRecalculationQueue}에 맡긴다. 이 메서드는 요청을
 * 넣기만 하므로 좋아요 요청 스레드에서 바로 끝난다. 예전에는 {@code @Async}로 좋아요마다 재계산을 바로
 * 돌렸는데, 상한이 없어 좋아요가 몰리면 재계산이 Qdrant를 다 차지했다(대기열 클래스 설명 참고).</p>
 */
@Component
@RequiredArgsConstructor
public class UserVectorUpdateListener {

    private final UserVectorRecalculationQueue recalculationQueue;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoardLikeChanged(BoardLikeChangedEvent event)
    {
        recalculationQueue.request(event.userId());
    }
}
