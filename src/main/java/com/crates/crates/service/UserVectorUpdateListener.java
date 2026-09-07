package com.crates.crates.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 좋아요 변화에 맞춰 취향 벡터를 다시 계산한다.
 *
 * <p><b>AFTER_COMMIT인 이유:</b> 그냥 @Async만 걸면 좋아요 트랜잭션이 커밋되기 전에 이 스레드가
 * 시작될 수 있다. 별도 커넥션이라 아직 커밋되지 않은 board_feedback 행이 보이지 않고, 결과적으로
 * 방금 누른 좋아요가 빠진 채로 벡터가 계산된다. 에러 없이 한 박자 늦은 값이 저장되는 형태라
 * 원인을 찾기 어렵다.</p>
 *
 * <p><b>@Async인 이유:</b> 재계산은 Qdrant 왕복이 붙어 수십~수백 ms가 걸린다. 사용자는 하트와
 * 좋아요 수만 받으면 되므로 기다릴 이유가 없다. 응답을 이미 보낸 뒤에 도는 작업이다.</p>
 *
 * <p><b>REQUIRES_NEW인 이유:</b> 커밋 이후 별도 스레드라 물려받을 트랜잭션이 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserVectorUpdateListener {

    private final UserVectorService userVectorService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBoardLikeChanged(BoardLikeChangedEvent event)
    {
        try
        {
            userVectorService.recalculateFor(event.userId());
        }
        catch (Exception e)
        {
            // 좋아요는 이미 커밋됐다. 여기서 예외를 올려도 되돌릴 것이 없고, 사용자는 이미 응답을 받았다.
            // 재시도 장치를 두지 않는 이유는 재계산이 좋아요 집합 전체를 보기 때문이다 —
            // 이번에 실패해도 다음 좋아요 때 올바른 값으로 저절로 맞춰진다.
            log.error("취향 벡터 재계산에 실패했습니다. userId: {}", event.userId(), e);
        }
    }
}
