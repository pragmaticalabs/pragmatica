package org.pragmatica.aether.slice.delegation;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Generalization of the Dormant/Active pattern for delegated control plane components.
public interface DelegatedComponent {
    Promise<Unit> activate();
    Promise<Unit> deactivate();
    TaskGroup taskGroup();
    boolean isActive();
}
