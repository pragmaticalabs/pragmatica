package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.lang.Option;

/// A value written on behalf of one holder (#1155): the token of the [HolderFenced] acquisition it was made under, or
/// none when it was made under no hold. Checked by the applier against the committed holder when the key is
/// [HolderGuarded].
public interface HolderBound {
    Option<String> boundHolder();
}
