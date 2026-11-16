package dev.spm.core.lua;

/** Token types and values for Lua 5.3 source lexing. */
record LuaToken(Kind kind, String lexeme, double number, int line) {
    enum Kind {
        EOF, NAME, NUMBER, STRING,
        AND, BREAK, DO, ELSE, ELSEIF, END, FALSE, FOR, FUNCTION, GOTO, IF, IN,
        LOCAL, NIL, NOT, OR, REPEAT, RETURN, THEN, TRUE, UNTIL, WHILE,
        PLUS, MINUS, STAR, SLASH, IDIV, PERCENT, CARET, HASH,
        AMP, TILDE, PIPE, LSHIFT, RSHIFT,
        CONCAT, DOTS, EQ, NEQ, LT, LE, GT, GE, ASSIGN,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACK, RBRACK, SEMI, COLON, DCOLON, COMMA, DOT
    }
}
