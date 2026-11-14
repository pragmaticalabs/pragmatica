package conduit.adapter.random;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;


/// The source of randomness a use case draws from, injected like `Clock` so tests can seed it. A step rather than a
/// bare `RandomGenerator`: the slice processor resolves a JDK interface as a slice dependency and has no way to build
/// one, while a step type with a static factory is built by the generated factory.
///
/// Each draw goes to the calling thread's `ThreadLocalRandom`, which is not shared between threads.
@FunctionalInterface
public interface Randomness extends RandomGenerator {
    static Randomness randomness() {
        return () -> ThreadLocalRandom.current().nextLong();
    }
}
