package conduit.view;

import java.util.List;


/// `{"tags": [...]}`: every tag attached to at least one article, byte order, no limit (B-40).
public record TagsResponse(List<String> tags) {
    public static TagsResponse tagsResponse(List<String> tags) {
        return new TagsResponse(List.copyOf(tags));
    }
}
