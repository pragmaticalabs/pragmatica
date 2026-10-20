package org.pragmatica.jbct.async;

import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;


/// Entry point of JBCT-ASYNC-01 (see [AsyncCallbackCheck]): installs the check as a javac task listener.
///
/// A module opts in by declaring `jbct-async-check` as a `provided` dependency, as it does for any annotation
/// processor; javac then initialises this processor for every compilation of the module (`*` makes it
/// unconditional), and the check runs after attribution, when receiver types and method symbols are exact.
///
/// Options: `-Ajbct.async.severity=warning|error|off` (default `warning`; `off` installs nothing). Under a compiler that is not javac there is no
/// `JavacTask`, and the check is not installed (a note says so).
@SupportedAnnotationTypes("*")
@SupportedOptions(AsyncCallbackProcessor.SEVERITY_OPTION)
public final class AsyncCallbackProcessor extends AbstractProcessor {
    static final String SEVERITY_OPTION = "jbct.async.severity";

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        install(env);
    }

    private boolean announced;

    /// Announces itself once, so that a build log which lacks the line shows the check never ran (an `annotationProcessorPaths`
    /// list that does not name `jbct-async-check` silently ignores the provided dependency).
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (!announced && !"off".equalsIgnoreCase(processingEnv.getOptions().get(SEVERITY_OPTION))) {
            announced = true;
            processingEnv.getMessager()
                         .printMessage(Diagnostic.Kind.NOTE,
                                       "JBCT-ASYNC-01 active (" + round.getRootElements().size() + " top-level types)");
        }

        return false;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    private static void install(ProcessingEnvironment env) {
        if ("off".equalsIgnoreCase(env.getOptions().get(SEVERITY_OPTION))) {
            return;
        }

        try {
            var severity = "error".equalsIgnoreCase(env.getOptions().get(SEVERITY_OPTION))
                           ? Diagnostic.Kind.ERROR
                           : Diagnostic.Kind.WARNING;

            JavacTask.instance(env)
                     .addTaskListener(AsyncCallbackCheck.asyncCallbackCheck(Trees.instance(env), severity));
        } catch (RuntimeException | LinkageError e) {
            env.getMessager().printMessage(Diagnostic.Kind.NOTE, "JBCT-ASYNC-01 is not checked: the compiler is not javac");
        }
    }
}
