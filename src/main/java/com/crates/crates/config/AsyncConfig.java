package com.crates.crates.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * @Async 작업 실행자.
 *
 * <p>지정하지 않으면 Spring이 SimpleAsyncTaskExecutor를 쓰는데, 그건 요청마다 플랫폼 스레드를
 * 새로 만들고 재사용하지 않는다. 취향 벡터 재계산은 좋아요마다 발생하므로 그대로 두면 좋아요가
 * 몰릴 때 스레드가 계속 생성된다.</p>
 *
 * <p>이 작업은 Qdrant 왕복과 DB 조회로 대부분의 시간을 대기에 쓰는 I/O 작업이라 가상 스레드가
 * 적합하다. 플랫폼 스레드를 붙잡고 기다리지 않으므로 풀 크기를 고민할 필요가 없다.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "applicationTaskExecutor")
    public Executor getAsyncExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 반환값이 없는 @Async 메서드에서 예외가 새면 기본적으로 조용히 사라진다.
     * 리스너가 자체적으로 잡고 있지만, 그 밖의 경로도 로그는 남게 해둔다.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
