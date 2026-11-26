package org.pragmatica.jbct.init;

import java.util.regex.Pattern;

import org.pragmatica.http.HttpClientError;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.jbct.shared.GitHubContentFetcher;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.jbct.init.AiToolsRef.AiToolsRefError.TagNotFound.tagNotFound;
import static org.pragmatica.jbct.init.AiToolsRef.AiToolsRefError.UnreleasedBuild.unreleasedBuild;


/// The coding-technology ref the AI tools are pinned to (#2363): the tag `ai-tools-v<jbct version>`, derived from
/// the running JBCT build's own version, never `main`. A build without a release tag (a `-SNAPSHOT` or a build whose
/// version is unknown) has no ref and is refused by name; a tag the repository does not carry is reported by name.
/// Neither case falls back to another ref.
///
/// @param tag the git tag in [#REPO] the tools are fetched at
public record AiToolsRef(String tag) {
    /// The repository the AI tools are published in.
    public static final String REPO = "siy/coding-technology";
    private static final String TAG_PREFIX = "ai-tools-v";

    private static final String STAMP_SEPARATOR = "@";

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?");
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    /// The ref of the running build, derived from the version stamped into this jar at build time.
    public static Result<AiToolsRef> aiToolsRef() {
        return aiToolsRef(GitHubVersionResolver.defaultJbctVersion());
    }

    /// The ref of the given JBCT build version.
    ///
    /// @param jbctVersion the JBCT build version, e.g. `1.0.0-rc5`
    /// @return the ref, or [AiToolsRefError.UnreleasedBuild] for a snapshot or unknown version
    public static Result<AiToolsRef> aiToolsRef(String jbctVersion) {
        return isReleased(jbctVersion)
               ? Result.success(new AiToolsRef(TAG_PREFIX + jbctVersion))
               : unreleasedBuild(jbctVersion).result();
    }

    /// Resolve the tag to the commit it points at; a tag the repository does not have is [AiToolsRefError.TagNotFound].
    public Result<String> resolveSha(HttpOperations http) {
        return GitHubContentFetcher.getLatestCommitSha(http, REPO, tag).mapError(this::asMissingTag);
    }

    /// The record of an install of this ref at `sha`: what is written into the cache and the project.
    public String stamp(String sha) {
        return tag + STAMP_SEPARATOR + sha;
    }

    /// True when `stamp` records an install of this ref, whatever commit the tag pointed at then.
    public boolean isStampOfThisRef(String stamp) {
        return stamp.startsWith(tag + STAMP_SEPARATOR);
    }

    private Cause asMissingTag(Cause cause) {
        return cause instanceof HttpClientError.RequestFailed failed && isMissingRef(failed.statusCode())
               ? tagNotFound(tag)
               : cause;
    }

    // GitHub answers an unknown ref on /commits/{ref} with 404 or 422 ("No commit found for SHA").
    private static boolean isMissingRef(int statusCode) {
        return statusCode == 404 || statusCode == 422;
    }

    private static boolean isReleased(String jbctVersion) {
        return VERSION.matcher(jbctVersion).matches() && !jbctVersion.endsWith(SNAPSHOT_SUFFIX);
    }

    /// Why the AI tools cannot be installed at the build's ref.
    public sealed interface AiToolsRefError extends Cause {
        /// The build has no release tag: a snapshot or a build of unknown version.
        record UnreleasedBuild(String version, String message) implements AiToolsRefError {
            public static UnreleasedBuild unreleasedBuild(String version) {
                return new UnreleasedBuild(version,
                                           "JBCT build '" + version
                                          + "' has no AI tools tag: looked for " + TAG_PREFIX + version
                                          + " in " + REPO
                                          + "; only released builds install AI tools (no fallback to main)");
            }
        }

        /// The repository carries no tag for a released version.
        record TagNotFound(String tag, String message) implements AiToolsRefError {
            public static TagNotFound tagNotFound(String tag) {
                return new TagNotFound(tag,
                                       "AI tools tag '" + tag
                                      + "' not found in " + REPO
                                      + ": it must be published before this release can install AI tools (no fallback to main)");
            }
        }
    }
}
