package dev.spm.core.lua;

import dev.spm.core.lua.LuaToken.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded pure-Java Lua 5.2/5.3 lexer. */
final class LuaLexer {
    private static final Map<String, Kind> KEYWORDS = Map.ofEntries(
            Map.entry("and", Kind.AND), Map.entry("break", Kind.BREAK), Map.entry("do", Kind.DO),
            Map.entry("else", Kind.ELSE), Map.entry("elseif", Kind.ELSEIF), Map.entry("end", Kind.END),
            Map.entry("false", Kind.FALSE), Map.entry("for", Kind.FOR), Map.entry("function", Kind.FUNCTION),
            Map.entry("goto", Kind.GOTO), Map.entry("if", Kind.IF), Map.entry("in", Kind.IN),
            Map.entry("local", Kind.LOCAL), Map.entry("nil", Kind.NIL), Map.entry("not", Kind.NOT),
            Map.entry("or", Kind.OR), Map.entry("repeat", Kind.REPEAT), Map.entry("return", Kind.RETURN),
            Map.entry("then", Kind.THEN), Map.entry("true", Kind.TRUE), Map.entry("until", Kind.UNTIL),
            Map.entry("while", Kind.WHILE));

    private final String src;
    private final String chunkName;
    private int pos;
    private int line = 1;

    LuaLexer(String chunkName, String src) {
        this.chunkName = chunkName;
        this.src = src;
    }

    List<LuaToken> tokenize() {
        var out = new ArrayList<LuaToken>();
        if (src.startsWith("#")) skipToEol(); // Unix shebang line
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                out.add(new LuaToken(Kind.EOF, "<eof>", 0.0, line));
                return out;
            }
            int tokLine = line;
            char c = src.charAt(pos);
            if (c == '_' || isAsciiLetter(c)) {
                int start = pos;
                while (pos < src.length() && (isAsciiAlnum(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                String word = src.substring(start, pos);
                Kind kw = KEYWORDS.get(word);
                out.add(new LuaToken(kw != null ? kw : Kind.NAME, word, 0.0, tokLine));
                continue;
            }
            if (isDigit(c) || (c == '.' && pos + 1 < src.length() && isDigit(src.charAt(pos + 1)))) {
                out.add(readNumber(tokLine));
                continue;
            }
            if (c == '\'' || c == '"') {
                out.add(new LuaToken(Kind.STRING, readQuotedString(c), 0.0, tokLine));
                continue;
            }
            if (c == '[') {
                int eq = longBracketEquals(pos);
                if (eq >= 0) {
                    out.add(new LuaToken(Kind.STRING, readLongBracket(eq, tokLine), 0.0, tokLine));
                    continue;
                }
                pos++;
                out.add(new LuaToken(Kind.LBRACK, "[", 0.0, tokLine));
                continue;
            }
            out.add(readPunctuation(tokLine));
        }
    }

    private LuaToken readPunctuation(int tokLine) {
        char c = src.charAt(pos++);
        return switch (c) {
            case '+' -> new LuaToken(Kind.PLUS, "+", 0.0, tokLine);
            case '-' -> new LuaToken(Kind.MINUS, "-", 0.0, tokLine);
            case '*' -> new LuaToken(Kind.STAR, "*", 0.0, tokLine);
            case '%' -> new LuaToken(Kind.PERCENT, "%", 0.0, tokLine);
            case '^' -> new LuaToken(Kind.CARET, "^", 0.0, tokLine);
            case '#' -> new LuaToken(Kind.HASH, "#", 0.0, tokLine);
            case '&' -> new LuaToken(Kind.AMP, "&", 0.0, tokLine);
            case '|' -> new LuaToken(Kind.PIPE, "|", 0.0, tokLine);
            case '(' -> new LuaToken(Kind.LPAREN, "(", 0.0, tokLine);
            case ')' -> new LuaToken(Kind.RPAREN, ")", 0.0, tokLine);
            case '{' -> new LuaToken(Kind.LBRACE, "{", 0.0, tokLine);
            case '}' -> new LuaToken(Kind.RBRACE, "}", 0.0, tokLine);
            case ']' -> new LuaToken(Kind.RBRACK, "]", 0.0, tokLine);
            case ';' -> new LuaToken(Kind.SEMI, ";", 0.0, tokLine);
            case ',' -> new LuaToken(Kind.COMMA, ",", 0.0, tokLine);
            case '/' -> match('/') ? new LuaToken(Kind.IDIV, "//", 0.0, tokLine)
                    : new LuaToken(Kind.SLASH, "/", 0.0, tokLine);
            case '~' -> match('=') ? new LuaToken(Kind.NEQ, "~=", 0.0, tokLine)
                    : new LuaToken(Kind.TILDE, "~", 0.0, tokLine);
            case '=' -> match('=') ? new LuaToken(Kind.EQ, "==", 0.0, tokLine)
                    : new LuaToken(Kind.ASSIGN, "=", 0.0, tokLine);
            case '<' -> match('=') ? new LuaToken(Kind.LE, "<=", 0.0, tokLine)
                    : (match('<') ? new LuaToken(Kind.LSHIFT, "<<", 0.0, tokLine)
                    : new LuaToken(Kind.LT, "<", 0.0, tokLine));
            case '>' -> match('=') ? new LuaToken(Kind.GE, ">=", 0.0, tokLine)
                    : (match('>') ? new LuaToken(Kind.RSHIFT, ">>", 0.0, tokLine)
                    : new LuaToken(Kind.GT, ">", 0.0, tokLine));
            case ':' -> match(':') ? new LuaToken(Kind.DCOLON, "::", 0.0, tokLine)
                    : new LuaToken(Kind.COLON, ":", 0.0, tokLine);
            case '.' -> {
                if (match('.')) {
                    yield match('.') ? new LuaToken(Kind.DOTS, "...", 0.0, tokLine)
                            : new LuaToken(Kind.CONCAT, "..", 0.0, tokLine);
                }
                yield new LuaToken(Kind.DOT, ".", 0.0, tokLine);
            }
            default -> throw syntax(tokLine, "unexpected symbol near '" + c + "'");
        };
    }

    private LuaToken readNumber(int tokLine) {
        int start = pos;
        if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
            pos += 2;
            while (pos < src.length() && isHex(src.charAt(pos))) pos++;
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && isHex(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'p' || src.charAt(pos) == 'P')) {
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
            }
        } else {
            while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
            }
        }
        String raw = src.substring(start, pos);
        Double parsed = LuaValues.parseNumberOrNull(raw);
        if (parsed == null) throw syntax(tokLine, "malformed number near '" + raw + "'");
        return new LuaToken(Kind.NUMBER, raw, parsed, tokLine);
    }

    private String readQuotedString(char quote) {
        int startLine = line;
        pos++; // opening quote
        var sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == quote) return sb.toString();
            if (c == '\n' || c == '\r') throw syntax(startLine, "unfinished string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) throw syntax(startLine, "unfinished string escape");
            char esc = src.charAt(pos++);
            switch (esc) {
                case 'a' -> sb.append('\u0007');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'v' -> sb.append('\u000B');
                case '\\', '"', '\'' -> sb.append(esc);
                case '\r' -> {
                    if (pos < src.length() && src.charAt(pos) == '\n') pos++;
                    line++;
                    sb.append('\n');
                }
                case '\n' -> {
                    line++;
                    sb.append('\n');
                }
                case 'z' -> {
                    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                        if (src.charAt(pos++) == '\n') line++;
                    }
                }
                case 'x' -> {
                    if (pos + 2 > src.length()) throw syntax(startLine, "hexadecimal digit expected");
                    String hex = src.substring(pos, pos + 2);
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw syntax(startLine, "invalid \\x escape");
                    }
                    pos += 2;
                }
                case 'u' -> {
                    if (pos >= src.length() || src.charAt(pos++) != '{') throw syntax(startLine, "missing '{' in \\u escape");
                    int hexStart = pos;
                    while (pos < src.length() && src.charAt(pos) != '}') pos++;
                    if (pos >= src.length()) throw syntax(startLine, "missing '}' in \\u escape");
                    String hex = src.substring(hexStart, pos++);
                    try {
                        int cp = Integer.parseInt(hex, 16);
                        sb.appendCodePoint(cp);
                    } catch (IllegalArgumentException e) {
                        throw syntax(startLine, "invalid UTF-8 escape");
                    }
                }
                default -> {
                    if (!isDigit(esc)) throw syntax(startLine, "invalid escape sequence '\\" + esc + "'");
                    int val = esc - '0';
                    for (int k = 0; k < 2 && pos < src.length() && isDigit(src.charAt(pos)); k++) {
                        val = val * 10 + (src.charAt(pos++) - '0');
                    }
                    if (val > 255) throw syntax(startLine, "decimal escape too large");
                    sb.append((char) val);
                }
            }
        }
        throw syntax(startLine, "unfinished string");
    }

    private String readLongBracket(int equals, int startLine) {
        pos += 2 + equals; // skip [=* [
        if (pos < src.length() && src.charAt(pos) == '\r') pos++;
        if (pos < src.length() && src.charAt(pos) == '\n') {
            pos++;
            line++;
        }
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
                continue;
            }
            if (c == ']' && matchClosingLongBracket(pos, equals)) {
                String text = src.substring(start, pos);
                pos += 2 + equals;
                return text;
            }
            pos++;
        }
        throw syntax(startLine, "unfinished long string");
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == '\u000B') {
                pos++;
                continue;
            }
            if (c == '\n') {
                line++;
                pos++;
                continue;
            }
            if (c == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
                pos += 2;
                int eq = pos < src.length() && src.charAt(pos) == '[' ? longBracketEquals(pos) : -1;
                if (eq >= 0) {
                    readLongBracket(eq, line);
                } else {
                    skipToEol();
                }
                continue;
            }
            break;
        }
    }

    private void skipToEol() {
        while (pos < src.length() && src.charAt(pos) != '\n') pos++;
    }

    private int longBracketEquals(int idx) {
        if (idx >= src.length() || src.charAt(idx) != '[') return -1;
        int i = idx + 1;
        while (i < src.length() && src.charAt(i) == '=') i++;
        return i < src.length() && src.charAt(i) == '[' ? i - (idx + 1) : -1;
    }

    private boolean matchClosingLongBracket(int idx, int equals) {
        if (idx + 1 + equals >= src.length() || src.charAt(idx) != ']') return false;
        for (int k = 1; k <= equals; k++) {
            if (src.charAt(idx + k) != '=') return false;
        }
        return src.charAt(idx + 1 + equals) == ']';
    }

    private boolean match(char expected) {
        if (pos < src.length() && src.charAt(pos) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private LuaException syntax(int errLine, String msg) {
        return new LuaException(LuaException.Reason.SYNTAX, chunkName + ":" + errLine + ": " + msg);
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isHex(char c) { return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }
    private static boolean isAsciiLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
    private static boolean isAsciiAlnum(char c) { return isAsciiLetter(c) || isDigit(c); }
}
