package org.pragmatica.aether.config.cluster;

import java.util.ArrayList;
import java.util.List;


/// Diff plan with actions ordered into waves. S9.3
public record DiffPlan(List<DiffAction> additions,
                       List<DiffAction> modifications,
                       List<DiffAction> removals,
                       List<DiffAction> immutable) {
    public DiffPlan {
        additions = List.copyOf(additions);
        modifications = List.copyOf(modifications);
        removals = List.copyOf(removals);
        immutable = List.copyOf(immutable);
    }

    public static DiffPlan diffPlan(List<DiffAction> additions,
                                    List<DiffAction> modifications,
                                    List<DiffAction> removals,
                                    List<DiffAction> immutable) {
        return new DiffPlan(additions, modifications, removals, immutable);
    }

    public boolean isEmpty() {
        return additions.isEmpty() && modifications.isEmpty() && removals.isEmpty() && immutable.isEmpty();
    }

    public boolean hasImmutableChanges() {
        return ! immutable.isEmpty();
    }

    public List<DiffAction> allActions() {
        var all = new ArrayList<DiffAction>(additions.size() + modifications.size() + removals.size() + immutable.size());
        all.addAll(additions);
        all.addAll(modifications);
        all.addAll(removals);
        all.addAll(immutable);
        return List.copyOf(all);
    }
}
