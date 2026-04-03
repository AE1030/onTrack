package org.tracker.gpatracker.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ServiceLogging {
    private static final Logger log = LoggerFactory.getLogger(ServiceLogging.class);

    @Pointcut("execution(* org.tracker.gpatracker.service.*.*(..))")
    public void serviceLayerPointcut() {

    }

    @Before("serviceLayerPointcut()")
    public void logBefore(JoinPoint joinPoint){
        if (log.isInfoEnabled()) {
            log.info("Entering method: {} with arguments: {}", joinPoint.getSignature().toShortString(), joinPoint.getArgs());
        }
    }

    @AfterReturning(pointcut = "serviceLayerPointcut()", returning = "result")
    public void logAfterReturning(JoinPoint joinPoint, Object result){
        if (log.isInfoEnabled()) {
            log.info("Exiting method: {} with result: {}", joinPoint.getSignature().toShortString(), result);
        }
    }

}
