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

    /**
     * Extracts the invoked {@link Method} from the join point.
     *
     * @return the method being called
     */
    @Override
    public Method getMethod() {
        return ((MethodSignature) pjp.getSignature()).getMethod();
    }

    /**
     * Extracts the arguments passed to the method call.
     *
     * @return the array of arguments
     */
    @Override
    public Object[] getArguments() {
        return pjp.getArgs();
    }

    /**
     * Proceeds to the next interceptor in the chain or the target method.
     *
     * @return the result of the call
     * @throws Throwable if the call fails
     */
    @Override
    public Object proceed() throws Throwable {
        return pjp.proceed();
    }

    /**
     * Returns the target object (the "this" reference).
     *
     * @return the target object
     */
    @Override
    public Object getThis() {
        return pjp.getTarget();
    }

    /**
     * Returns the static part of the join point (the method).
     *
     * @return the method as an {@link AccessibleObject}
     */
    @Override
    public AccessibleObject getStaticPart() {
        return getMethod();
    }
}
