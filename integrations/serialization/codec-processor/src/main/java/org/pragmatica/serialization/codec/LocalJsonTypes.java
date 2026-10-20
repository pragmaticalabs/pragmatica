package org.pragmatica.serialization.codec;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import static org.pragmatica.lang.Option.option;


/// Reports `@Json` on a type that annotation processing never sees, so that it would silently get no codec: a local
/// type (declared in a method, constructor, initialiser or lambda body), or a type nested in a local or anonymous
/// class. Processors are handed only top-level types and their members, so these are found after attribution instead,
/// by a javac task listener scanning each analysed class: one compile error per such type, at its declaration.
///
/// The listener is javac's API. Under another compiler (no `JavacTask` for the processing environment) the check is
/// not installed. It runs whenever the processor is initialised, which javac does when the compilation contains any
/// annotation outside method bodies; a compilation whose only annotation is a local `@Json` does not reach it.
final class LocalJsonTypes implements TaskListener {
    private static final Set<NestingKind> UNSEEN_NESTING = EnumSet.of(NestingKind.LOCAL, NestingKind.ANONYMOUS);

    private final Trees trees;

    private LocalJsonTypes(Trees trees) {
        this.trees = trees;
    }

    /// Installs the check in the compilation behind `env`; fails when that compilation is not a javac task.
    static Result<Unit> install(ProcessingEnvironment env) {
        return Result.lift(Causes::fromThrowable,
                           () -> JavacTask.instance(env))
                     .flatMap(task -> Result.lift(Causes::fromThrowable,
                                                  () -> task.addTaskListener(new LocalJsonTypes(Trees.instance(env)))));
    }

    @Contract
    @Override
    public void finished(TaskEvent event) {
        option(event.getTypeElement()).filter(_ -> event.getKind() == TaskEvent.Kind.ANALYZE)
              .flatMap(type -> option(trees.getPath(type)))
              .onPresent(path -> new Scanner().scan(path, null));
    }

    private Unit reportIfUnseen(TreePath path) {
        option(trees.getElement(path)).filter(TypeElement.class::isInstance)
              .map(TypeElement.class::cast)
              .filter(LocalJsonTypes::isUnseenJsonType)
              .onPresent(type -> report(type, path));

        return Unit.unit();
    }

    private static boolean isUnseenJsonType(TypeElement type) {
        return isJson(type) && isInsideCode(type);
    }

    private static boolean isJson(TypeElement type) {
        return type.getAnnotationMirrors()
                   .stream()
                   .anyMatch(mirror -> mirror.getAnnotationType()
                                             .toString()
                                             .equals(CodecProcessor.JSON));
    }

    // The type itself or a type enclosing it is local or anonymous: none of them is a member reachable from a
    // top-level type, which is all annotation processing visits.
    private static boolean isInsideCode(TypeElement type) {
        return Stream.<Element> iterate(type, Objects::nonNull, Element::getEnclosingElement).anyMatch(LocalJsonTypes::isLocalOrAnonymous);
    }

    private static boolean isLocalOrAnonymous(Element element) {
        return element instanceof TypeElement type && UNSEEN_NESTING.contains(type.getNestingKind());
    }

    private Unit report(TypeElement type, TreePath path) {
        trees.printMessage(Diagnostic.Kind.ERROR,
                           "@Json cannot be applied to '" + type.getSimpleName()
                          + "': it is declared inside a method, initializer or anonymous class, where annotation"
                          + " processing does not see it, so it would get no codec. Declare it as a top-level or"
                          + " member type.",
                           path.getLeaf(),
                           path.getCompilationUnit());

        return Unit.unit();
    }

    /// Visits every class declaration of one analysed top-level class, its local and anonymous classes included.
    private final class Scanner extends TreePathScanner<Void, Void> {
        @Contract
        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            reportIfUnseen(getCurrentPath());

            return super.visitClass(tree, unused);
        }
    }
}
