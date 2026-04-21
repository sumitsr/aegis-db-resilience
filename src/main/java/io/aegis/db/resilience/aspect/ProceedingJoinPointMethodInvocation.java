package io.aegis.db.resilience.aspect;

import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;

/**
 * Adapts a {@link ProceedingJoinPoint} to the {@link MethodInvocation} interface so
 * {@link DatabaseResilienceInterceptor} can work uniformly whether called from an
 * AspectJ aspect or an AOP {@link org.springframework.aop.Advisor}.
 */
final class ProceedingJoinPointMethodInvocation implements MethodInvocation {

    private final ProceedingJoinPoint pjp;

    ProceedingJoinPointMethodInvocation(ProceedingJoinPoint pjp) {
        this.pjp = pjp;
    }

    @Override
    public Method getMethod() {
        return ((MethodSignature) pjp.getSignature()).getMethod();
    }

    @Override
    public Object[] getArguments() {
        return pjp.getArgs();
    }

    @Override
    public Object proceed() throws Throwable {
        return pjp.proceed();
    }

    @Override
    public Object getThis() {
        return pjp.getTarget();
    }

    @Override
    public AccessibleObject getStaticPart() {
        return getMethod();
    }
}
