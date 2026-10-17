package org.pragmatica.serialization.codec;

import java.util.HashMap;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.utils.Causes.cause;


/// The classes the JSON side of this processor generates in one compilation, by qualified name, with what each is
/// generated for. Entry-point names are injective (`JsonNames`), so two types never need the same one; this is the
/// backstop for any generated name that is not, such as two type sets of a package whose registry hashes collide,
/// reported as one clean error here instead of the Filer's "attempt to recreate" exception with a stack trace. A class
/// generated in this compilation is not yet visible through `Elements` in the round that writes it, so
/// `JsonCodecGenerator.ensureFree` alone cannot see this case.
final class GeneratedNames {
    private final Map<String, String> owners = new HashMap<>();

    /// Claims `qualified` for `owner`; fails naming both when another owner already claimed it.
    Result<Unit> claim(String qualified, String owner) {
        return option(owners.putIfAbsent(qualified, owner)).filter(previous -> !previous.equals(owner))
                     .map(previous -> cause("Cannot generate " + qualified
                                           + " for " + owner
                                           + ": it is already generated for " + previous
                                           + ". Rename one of the two types."))
                     .map(Cause::<Unit> result)
                     .or(Result::unitResult);
    }
}
