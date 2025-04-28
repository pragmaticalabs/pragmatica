package org.pragmatica.utility;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

public interface HierarchyScanner {
    @SuppressWarnings("unchecked")
    static <T> Set<Class<? extends T>> concreteSubtypes(Class<T> type) {
        var result = new HashSet<Class<? extends T>>();

        if (!type.isInterface()) {
            return result;
        }

        var queue = new LinkedBlockingQueue<Class<?>>();
        queue.offer(type);

        while (!queue.isEmpty()) {
            var currentInterface = queue.poll();

            if (!currentInterface.isSealed()) {
                continue;
            }

            for (var clazz : currentInterface.getPermittedSubclasses()) {
                if (clazz.isInterface()) {
                    queue.offer(clazz);
                } else  {
                    result.add((Class<? extends T>) clazz);
                }
            }
        }
        return result;
    }
}
