package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


public class Comments<T> {
    public static Result<String> markdownDoc(String value) {
        return Result.success(value);
    }

    public String shortDoc() {
        return "";
    }

    public void withLinks() {}

    @Deprecated public String traditionalJavaDoc(String first, String second) {
        return first + second;
    }

    private String field;

    public void methodWithComment() {
        var x = 1;
        if (x > 0) {process(x);}
    }

    public void blockComment() {}

    public void singleLineBlock() {}

    public void todoComment() {}

    public void fixmeComment() {}

    public void noteComment() {}

    private static final int MAX_SIZE = 100;

    private String name = "default";

    public static <T> Comments<T> comments() {
        return new Comments();
    }

    public T getValue() {
        return null;
    }

    public void inlineComments() {
        var a = 1;
        var b = 2;
        var c = a + b;
    }

    public Result<String> codeInComment() {
        return Result.success("");
    }

    public Result<String> tableDoc(String value, Option<String> option) {
        return Result.success(value);
    }

    enum Status {
        PENDING,
        ACTIVE,
        COMPLETED
    }

    record Entity(String id, String name){}

    void process(int x) {}
}
