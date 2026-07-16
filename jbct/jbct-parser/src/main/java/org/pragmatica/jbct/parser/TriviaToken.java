package org.pragmatica.jbct.parser;

import org.pragmatica.peg.v6.token.TokenArray;


/// View over a single trivia token in a TokenArray. Trivia tokens are produced by the v6
/// lexer for whitespace runs and comments, and live in the TokenArray inline with regular
/// tokens (per the `%whitespace` grammar directive). Consumers receive token indices from
/// `Cursor.leadingTriviaTokens()` / `Cursor.trailingTriviaTokens()` and wrap them in
/// `TriviaToken` instances when they need text + kind together.
public record TriviaToken(TokenArray tokens, int idx) {
    /// Token kind id; matches `TokenArray.KIND_WHITESPACE` / `KIND_LINE_COMMENT` /
    /// `KIND_BLOCK_COMMENT` / `KIND_DOC_LINE_COMMENT` / `KIND_DOC_BLOCK_COMMENT`.
    public int kind() {
        return tokens.kindAt(idx);
    }

    public boolean isWhitespace() {
        return kind() == TokenArray.KIND_WHITESPACE;
    }

    /// True for `//` and `///` style line comments (KIND_LINE_COMMENT or KIND_DOC_LINE_COMMENT).
    public boolean isLineComment() {
        int k = kind();

        return k == TokenArray.KIND_LINE_COMMENT || k == TokenArray.KIND_DOC_LINE_COMMENT;
    }

    /// True for `/* … */` and `/** … */` style block comments.
    public boolean isBlockComment() {
        int k = kind();

        return k == TokenArray.KIND_BLOCK_COMMENT || k == TokenArray.KIND_DOC_BLOCK_COMMENT;
    }

    /// True for `///` or `/** … */` doc-style comments. Note: the formatter currently
    /// treats DOC_LINE_COMMENT and LINE_COMMENT identically for emission; this method is
    /// available for lint rules that care about doc-vs-plain distinction.
    public boolean isDocComment() {
        int k = kind();

        return k == TokenArray.KIND_DOC_LINE_COMMENT || k == TokenArray.KIND_DOC_BLOCK_COMMENT;
    }

    public boolean isComment() {
        return isLineComment() || isBlockComment();
    }

    public CharSequence text() {
        return tokens.textAt(idx);
    }

    public int start() {
        return tokens.startAt(idx);
    }

    public int end() {
        return tokens.endAt(idx);
    }
}
