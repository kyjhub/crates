package com.crates.crates.scheduler;

import com.crates.crates.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupScheduler {

    private final RefreshTokenService refreshTokenService;

    @Scheduled(cron = "${scheduler.refresh-token-cleanup.cron}", zone = "Asia/Seoul")
    public void cleanUp() {
        refreshTokenService.deleteExpiredTokens();
    }
}
