package org.tracker.gpatracker.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ExecutionTrackerAspect {
    private static final Logger log = LoggerFactory.getLogger(ExecutionTrackerAspect.class);

    @Around("@annotation(org.tracker.gpatracker.annotation.TrackExecution)")
    public Object trackerExecution(ProceedingJoinPoint joinPoint) {
        Long start = System.currentTimeMillis();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            throw new IllegalStateException("Method execution failed: " + joinPoint.getSignature().toShortString(), e);
        }
        Long end = System.currentTimeMillis();
        if (log.isInfoEnabled()) {
            log.info("Execution time of {} : {} ms", joinPoint.getSignature().toShortString(), (end - start));
        }
        return result;
    }

}
