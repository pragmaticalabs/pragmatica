package org.pragmatica.jbct.cli.skeleton;

import org.junit.jupiter.api.Test;

import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.resource;
import static org.assertj.core.api.Assertions.assertThat;


/// A source that does not parse yields its error with a position and never a partial skeleton.
class SkeletonParseErrorTest {
    @Test
    void aSyntaxError_yieldsAPositionedFailure_andNoSkeleton() {
        var result = Skeletons.skeleton(resource("Broken.java.txt"), true);

        assertThat(result.isFailure()).isTrue();
        result.onSuccess(skeleton -> {
                  throw new AssertionError("a partial skeleton was produced: " + skeleton);
              })
              .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(SourceNotParsed.class,
                                                                           error -> assertThat(error.row()).isBetween(1,
                                                                                                                      8)))
              .onFailure(cause -> assertThat(cause.message()).matches("\\d+:\\d+: .+"));
    }

    @Test
    void theFirstMemberBeforeTheError_isNotListedEither() {
        var result = Skeletons.skeleton(resource("Broken.java.txt"), false);

        assertThat(result.isFailure()).isTrue();
    }
}
