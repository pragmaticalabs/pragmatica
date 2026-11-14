package conduit.domain;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// A tag in the vocabulary.
public record Tag(String name) {
    /// Invariant: a non-blank name (B-26 rejects a blank tag on write).
    ///
    /// Generated code (target/) still builds this through `::new`, bypassing the factory: a known generator bug (pragmatica #3018).
    public static Result<Tag> tag(String name) {
        return Verify.ensure(name, Verify.Is::notBlank, Invalid.BLANK).map(Tag::new);
    }

    /// The names of the tags, in order.
    public static List<String> names(List<Tag> tags) {
        return tags.stream()
                   .map(Tag::name)
                   .toList();
    }

    /// The invariant violations of this record, each a typed cause.
    public enum Invalid implements Cause {
        BLANK("tag name is blank");
        private final String message;
        Invalid(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
