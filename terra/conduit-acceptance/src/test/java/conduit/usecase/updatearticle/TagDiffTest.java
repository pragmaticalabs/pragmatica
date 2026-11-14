package conduit.usecase.updatearticle;

import java.util.List;

import conduit.domain.TagList;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;

class TagDiffTest {
    @Test
    void removesOnlyWhatLeft_addsOnlyWhatIsNew() {
        var diff = UpdateArticle.TagDiff.tagDiff(List.of("a", "b", "c"), success(TagList.tagList(Option.option(List.of("b", "c", "d")))));

        assertThat(diff.removed()).containsExactly("a");
        assertThat(diff.added()).containsExactly("d");
    }

    @Test
    void emptyWantedListRemovesEverything() {
        var diff = UpdateArticle.TagDiff.tagDiff(List.of("a", "b"), TagList.empty());

        assertThat(diff.removed()).containsExactly("a", "b");
        assertThat(diff.added()).isEmpty();
    }
}
