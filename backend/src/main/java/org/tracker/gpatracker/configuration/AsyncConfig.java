package org.tracker.gpatracker.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.tracker.gpatracker.tenancy.TenantTaskDecorator;

import java.util.concurrent.Executor;

/**
 * Async execution, with the submitting thread's tenant carried onto the worker.
 *
 * <p>Without the decorator, {@code @Async} methods run with no tenant bound, so anything that
 * resolves the current student throws — and where that exception is caught and logged, the work
 * silently does not happen.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    /** Core pool size is also what the pool-reuse leak test relies on. */
    static final int CORE_POOL_SIZE = 8;

    @Bean
    public ThreadPoolTaskExecutor tenantAwareTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(new TenantTaskDecorator());
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return tenantAwareTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            logger.error("Uncaught exception in @Async method {}", method.getName(), throwable);
            new SimpleAsyncUncaughtExceptionHandler().handleUncaughtException(throwable, method, params);
        };
    }
}
