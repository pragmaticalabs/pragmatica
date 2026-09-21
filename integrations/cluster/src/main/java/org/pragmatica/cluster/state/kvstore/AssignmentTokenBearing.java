package org.pragmatica.cluster.state.kvstore;

/// A value carrying an assignment token — the authority record committed under an
/// [AssignmentGuarded] key's [AssignmentGuarded#guardKey], or a write to such a key claiming to be made
/// under that authority (#1271). Tokens are compared with `equals`.
public interface AssignmentTokenBearing {
    Object guardToken();
}
