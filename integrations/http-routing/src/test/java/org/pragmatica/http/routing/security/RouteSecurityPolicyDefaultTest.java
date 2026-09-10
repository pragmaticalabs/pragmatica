package org.pragmatica.http.routing.security;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #876 tripwire: [RouteSecurityPolicy#canAccess] must have NO default implementation.
///
/// The defect this pins is not a behavior a request can exercise — `canAccess` has zero callers in
/// main code, so there is no HTTP boundary to assert a 401 at, and a test that stood one up would
/// pass whatever the default said. The observable IS the interface shape: while the method carried
/// `default { return Access.ALLOW; }`, any implementor that omitted it permitted every request,
/// silently and invisibly to the compiler. Removing the default makes the omission a COMPILE error,
/// which no runtime test can assert directly; this asserts the property that makes it one.
///
/// Reflection rather than a compile-fail harness because the fix must survive a well-meaning
/// "restore the convenient default" edit years from now, and that edit reddens this in one line.
class RouteSecurityPolicyDefaultTest {
    @Test
    void canAccess_isAbstract_soAnUnoverridingImplementorCannotCompile() throws Exception {
        var canAccess = RouteSecurityPolicy.class.getMethod("canAccess", RequestSecurityContext.class);

        assertThat(canAccess.isDefault())
            .as("#876: a default canAccess lets an implementor inherit an answer it never chose")
            .isFalse();
        assertThat(Modifier.isAbstract(canAccess.getModifiers()))
            .as("#876: canAccess must be abstract, so omitting it is a compile error")
            .isTrue();
    }

    @Test
    void routeSecurityPolicy_declaresNoDefaultMethods_soNothingIsAnsweredByOmission() {
        var defaults = Arrays.stream(RouteSecurityPolicy.class.getDeclaredMethods())
                             .filter(Method::isDefault)
                             .map(Method::getName)
                             .toList();

        assertThat(defaults)
            .as("#876: a security interface must not answer anything by default")
            .isEmpty();
    }

    /// Positive control for the instrument above: the same reflection lookup, applied to the
    /// implementor that DOES declare `canAccess`, reports it concrete. Without this, "abstract" and
    /// "the lookup found nothing useful" would read identically.
    @Test
    void permitAll_declaresConcreteCanAccess_provingTheReflectionLookupDiscriminates() throws Exception {
        var declared = RouteSecurityPolicy.PermitAll.class.getMethod("canAccess", RequestSecurityContext.class);

        assertThat(Modifier.isAbstract(declared.getModifiers())).isFalse();
        assertThat(declared.isDefault()).isFalse();
    }

    @Test
    void permitAll_allowsEveryCaller_statingTheGrantInsteadOfInheritingIt() {
        assertThat(RouteSecurityPolicy.permitAll().canAccess(null)).isEqualTo(Access.ALLOW);
    }
}
