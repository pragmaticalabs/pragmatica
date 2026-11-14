package conduit.domain;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.domain.ConduitError.RequestInvalid;


/// The tags of an article: case-sensitive, stored as sent, exact duplicates collapsed, kept in byte order (B-26,
/// B-27). Byte order is also the insert order for new tag rows, which is what keeps two concurrent writers from
/// locking the same names in opposite orders.
public record TagList(List<String> names) {
    private static final RequestInvalid BLANK = RequestInvalid.blank("tagList");
    private static final TagList EMPTY = new TagList(List.of());

    /// A list as sent. `null` (as distinct from absent) and any blank tag are `tagList: can't be blank`.
    public static Result<TagList> tagList(Option<List<String>> raw) {
        return raw.toResult(BLANK)
                  .filter(BLANK, TagList::noBlankTag)
                  .map(TagList::sorted);
    }

    public static TagList empty() {
        return EMPTY;
    }

    public String[] asArray() {
        return names.toArray(String[]::new);
    }

    private static boolean noBlankTag(List<String> raw) {
        return raw.stream()
                  .allMatch(Verify.Is::present);
    }

    /// `String.compareTo` orders by UTF-16 code unit, which differs from UTF-8 byte order (`COLLATE "C"`) only for
    /// supplementary characters against U+E000..U+FFFF. The comparator below is the byte order.
    private static TagList sorted(List<String> raw) {
        var set = new TreeSet<String>(TagList::compareUtf8);

        set.addAll(raw);

        return new TagList(List.copyOf(set));
    }

    static int compareUtf8(String left, String right) {
        return Arrays.compare(left.codePoints().toArray(),
                              right.codePoints().toArray());
    }
}
