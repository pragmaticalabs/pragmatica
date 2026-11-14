package conduit.shared;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import conduit.domain.ArticleId;
import conduit.domain.ArticleListing;
import conduit.persistence.TagPersistence.ArticleTagRow;
import conduit.persistence.TagPersistence;
import conduit.view.ArticleSummary;
import conduit.view.ArticlesResponse;


/// Assembling one page of article summaries: the page rows, their tag lists in one more query, and the count.
public sealed interface ArticlePages {
    static Promise<ArticlesResponse> assemble(TagPersistence tags, List<ArticleListing> rows, long count) {
        return rows.isEmpty()
               ? ArticlesResponse.articlesResponse(List.of(),
                                                   count)
                                 .async()
               : withTags(tags, rows, count);
    }

    private static Promise<ArticlesResponse> withTags(TagPersistence tags, List<ArticleListing> rows, long count) {
        var ids = rows.stream().mapToLong(row -> row.id()
                                                    .value()).toArray();

        return tags.findByArticles(ids)
                   .flatMap(tagRows -> summaries(rows,
                                                 byArticle(tagRows)).flatMap(list -> ArticlesResponse.articlesResponse(list,
                                                                                                                       count))
                                                .async());
    }

    private static Map<ArticleId, List<String>> byArticle(List<ArticleTagRow> tagRows) {
        return tagRows.stream()
                      .collect(Collectors.groupingBy(ArticleTagRow::articleId,
                                                     Collectors.mapping(ArticleTagRow::name,
                                                                        Collectors.toList())));
    }

    private static Result<List<ArticleSummary>> summaries(List<ArticleListing> rows,
                                                          Map<ArticleId, List<String>> tags) {
        return Result.allOf(rows.stream().map(row -> Views.summary(row,
                                                                   tags.getOrDefault(row.id(), List.of()))));
    }

    record unused() implements ArticlePages {}
}
