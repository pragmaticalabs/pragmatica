package org.pragmatica.aether.slice.serialization;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.ClassRegistrator;

import java.util.ArrayList;
import java.util.List;

import static org.pragmatica.utility.HierarchyScanner.concreteSubtypes;

/// Registers core framework types for slice-level Fury serialization.
///
/// These classes are loaded by FrameworkClassLoader and are the same Class objects
/// in every slice bridge. Sequential ID registration ensures consistent IDs.
@SuppressWarnings("JBCT-VO-01")
public interface SliceCoreClasses extends ClassRegistrator {
    SliceCoreClasses INSTANCE = new SliceCoreClasses() {};

    @Override
    default List<Class<?>> classesToRegister() {
        var classes = new ArrayList<Class<?>>();
        classes.addAll(concreteSubtypes(Option.class));
        classes.addAll(concreteSubtypes(Result.class));
        classes.add(Unit.class);
        return List.copyOf(classes);
    }
}
