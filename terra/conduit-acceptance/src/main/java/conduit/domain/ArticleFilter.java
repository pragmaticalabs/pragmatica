package conduit.domain;

import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// The filters of `GET /articles` (B-38): combined with AND, unknown values match nothing (200, empty, count 0).
/// Each shape is its own case because each has its own statement (see ArticlePersistence).
public sealed interface ArticleFilter {
    record All() implements ArticleFilter {
        public static All all() {
            return new All();
        }
    }

    record ByTag(String tag) implements ArticleFilter {
        public static ByTag byTag(String tag) {
            return new ByTag(tag);
        }
    }

    record ByAuthor(String author) implements ArticleFilter {
        public static ByAuthor byAuthor(String author) {
            return new ByAuthor(author);
        }
    }

    record ByFavorited(String username) implements ArticleFilter {
        public static ByFavorited byFavorited(String username) {
            return new ByFavorited(username);
        }
    }

    record Combined(Option<String> tag, Option<String> author, Option<String> favorited) implements ArticleFilter {
        public static Combined combined(Option<String> tag, Option<String> author, Option<String> favorited) {
            return new Combined(tag, author, favorited);
        }
    }

    static ArticleFilter articleFilter(Option<String> tag, Option<String> author, Option<String> favorited) {
        var count = Stream.of(tag, author, favorited).filter(Option::isPresent).count();

        return count > 1
               ? Combined.combined(tag, author, favorited)
               : single(tag, author, favorited);
    }

    private static ArticleFilter single(Option<String> tag, Option<String> author, Option<String> favorited) {
        var byAuthorOrFavorited = author.<ArticleFilter> map(ByAuthor::byAuthor)
                                        .orElse(() -> favorited.map(ByFavorited::byFavorited));

        return tag.<ArticleFilter> map(ByTag::byTag)
                  .orElse(byAuthorOrFavorited)
                  .or(All.all());
    }
}
