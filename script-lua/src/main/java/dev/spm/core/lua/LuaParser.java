package dev.spm.core.lua;

import dev.spm.core.lua.LuaAst.*;
import dev.spm.core.lua.LuaToken.Kind;
import java.util.ArrayList;
import java.util.List;

/** Recursive-descent parser with precedence climbing for Lua 5.2/5.3 grammar. */
final class LuaParser {
    private static final int MAX_AST_DEPTH = 192;

    private final String chunkName;
    private final List<LuaToken> tokens;
    private int index;
    private int depth;

    LuaParser(String chunkName, List<LuaToken> tokens) {
        this.chunkName = chunkName;
        this.tokens = tokens;
    }

    Chunk parse() {
        var stmts = parseBlock();
        if (peek().kind() != Kind.EOF) {
            throw error(peek(), "unexpected symbol near '" + peek().lexeme() + "'");
        }
        return new Chunk(chunkName, stmts);
    }

    private List<Stmt> parseBlock() {
        enter();
        try {
            var list = new ArrayList<Stmt>();
            while (!isBlockEnd(peek().kind())) {
                Stmt s = parseStatement();
                if (!(s instanceof EmptyStmt)) list.add(s);
                if (s instanceof ReturnStmt) {
                    while (match(Kind.SEMI)) {}
                    if (!isBlockEnd(peek().kind())) {
                        throw error(peek(), "'end' expected after 'return'");
                    }
                    break;
                }
            }
            return List.copyOf(list);
        } finally {
            leave();
        }
    }

    private boolean isBlockEnd(Kind k) {
        return k == Kind.EOF || k == Kind.END || k == Kind.ELSE || k == Kind.ELSEIF || k == Kind.UNTIL;
    }

    private Stmt parseStatement() {
        enter();
        try {
            if (match(Kind.SEMI)) return new EmptyStmt();
            LuaToken t = peek();
            return switch (t.kind()) {
                case DO -> {
                    advance();
                    var body = parseBlock();
                    expect(Kind.END, "to close 'do'");
                    yield new DoStmt(body);
                }
                case WHILE -> {
                    advance();
                    Expr cond = parseExpr();
                    expect(Kind.DO, "after 'while' condition");
                    var body = parseBlock();
                    expect(Kind.END, "to close 'while'");
                    yield new WhileStmt(cond, body, t.line());
                }
                case REPEAT -> {
                    advance();
                    var body = parseBlock();
                    expect(Kind.UNTIL, "to close 'repeat'");
                    Expr cond = parseExpr();
                    yield new RepeatStmt(body, cond, t.line());
                }
                case IF -> parseIf(t.line());
                case FOR -> parseFor(t.line());
                case FUNCTION -> parseGlobalFunction(t.line());
                case LOCAL -> parseLocal(t.line());
                case BREAK -> {
                    advance();
                    yield new BreakStmt(t.line());
                }
                case RETURN -> parseReturn(t.line());
                default -> parseExprOrAssign();
            };
        } finally {
            leave();
        }
    }

    private Stmt parseIf(int line) {
        advance(); // if
        var branches = new ArrayList<IfBranch>();
        Expr cond = parseExpr();
        expect(Kind.THEN, "after 'if' condition");
        branches.add(new IfBranch(cond, parseBlock(), line));
        while (match(Kind.ELSEIF)) {
            int elifLine = previous().line();
            Expr econd = parseExpr();
            expect(Kind.THEN, "after 'elseif' condition");
            branches.add(new IfBranch(econd, parseBlock(), elifLine));
        }
        List<Stmt> elseBody = List.of();
        if (match(Kind.ELSE)) elseBody = parseBlock();
        expect(Kind.END, "to close 'if'");
        return new IfStmt(List.copyOf(branches), elseBody);
    }

    private Stmt parseFor(int line) {
        advance(); // for
        String firstVar = expect(Kind.NAME, "expected variable name after 'for'").lexeme();
        if (match(Kind.ASSIGN)) {
            Expr start = parseExpr();
            expect(Kind.COMMA, "after numeric 'for' initial value");
            Expr limit = parseExpr();
            Expr step = match(Kind.COMMA) ? parseExpr() : new LiteralExpr(LuaValue.of(1L));
            expect(Kind.DO, "after 'for' range");
            var body = parseBlock();
            expect(Kind.END, "to close numeric 'for'");
            return new NumericForStmt(firstVar, start, limit, step, body, line);
        }
        var vars = new ArrayList<String>();
        vars.add(firstVar);
        while (match(Kind.COMMA)) {
            vars.add(expect(Kind.NAME, "expected variable name in 'for' list").lexeme());
        }
        expect(Kind.IN, "expected '=' or 'in' in 'for'");
        var iters = parseExprList();
        expect(Kind.DO, "after 'for' iterator list");
        var body = parseBlock();
        expect(Kind.END, "to close generic 'for'");
        return new GenericForStmt(List.copyOf(vars), iters, body, line);
    }

    private Stmt parseGlobalFunction(int line) {
        advance(); // function
        var first = expect(Kind.NAME, "expected function name");
        LValue target = new VarTarget(first.lexeme(), first.line());
        var nameBuilder = new StringBuilder(first.lexeme());
        while (match(Kind.DOT)) {
            var field = expect(Kind.NAME, "expected field name");
            nameBuilder.append('.').append(field.lexeme());
            target = new IndexTarget(lvalueToExpr(target), new LiteralExpr(LuaValue.of(field.lexeme())), field.line());
        }
        boolean isMethod = false;
        if (match(Kind.COLON)) {
            var method = expect(Kind.NAME, "expected method name");
            nameBuilder.append(':').append(method.lexeme());
            target = new IndexTarget(lvalueToExpr(target), new LiteralExpr(LuaValue.of(method.lexeme())), method.line());
            isMethod = true;
        }
        FunctionExpr fn = parseFuncBody(nameBuilder.toString(), isMethod, line);
        return new AssignStmt(List.of(target), List.of(fn), line);
    }

    private Stmt parseLocal(int line) {
        advance(); // local
        if (match(Kind.FUNCTION)) {
            var name = expect(Kind.NAME, "expected local function name");
            FunctionExpr fn = parseFuncBody(name.lexeme(), false, line);
            return new LocalFuncStmt(name.lexeme(), fn);
        }
        var names = new ArrayList<String>();
        names.add(expect(Kind.NAME, "expected local variable name").lexeme());
        while (match(Kind.COMMA)) {
            names.add(expect(Kind.NAME, "expected local variable name").lexeme());
        }
        List<Expr> values = match(Kind.ASSIGN) ? parseExprList() : List.of();
        return new LocalStmt(List.copyOf(names), values, line);
    }

    private Stmt parseReturn(int line) {
        advance(); // return
        if (isBlockEnd(peek().kind()) || peek().kind() == Kind.SEMI) {
            return new ReturnStmt(List.of(), line);
        }
        return new ReturnStmt(parseExprList(), line);
    }

    private Stmt parseExprOrAssign() {
        Expr first = parseSuffixedExpr();
        if (peek().kind() == Kind.ASSIGN || peek().kind() == Kind.COMMA) {
            var targets = new ArrayList<LValue>();
            targets.add(toLValue(first));
            while (match(Kind.COMMA)) {
                targets.add(toLValue(parseSuffixedExpr()));
            }
            expect(Kind.ASSIGN, "syntax error");
            var values = parseExprList();
            return new AssignStmt(List.copyOf(targets), values, firstLine(first));
        }
        if (first instanceof CallExpr || first instanceof MethodCallExpr) {
            return new ExprStmt(first);
        }
        throw error(peek(), "syntax error: expected assignment or function call");
    }

    private LValue toLValue(Expr e) {
        if (e instanceof VarExpr v) return new VarTarget(v.name(), v.line());
        if (e instanceof FieldExpr f) return new IndexTarget(f.table(), f.key(), f.line());
        throw error(peek(), "syntax error in assignment target");
    }

    private Expr lvalueToExpr(LValue lv) {
        if (lv instanceof VarTarget v) return new VarExpr(v.name(), v.line());
        IndexTarget idx = (IndexTarget) lv;
        return new FieldExpr(idx.table(), idx.key(), idx.line());
    }

    private List<Expr> parseExprList() {
        var list = new ArrayList<Expr>();
        list.add(parseExpr());
        while (match(Kind.COMMA)) list.add(parseExpr());
        return List.copyOf(list);
    }

    private Expr parseExpr() {
        enter();
        try {
            return parseSubExpr(0);
        } finally {
            leave();
        }
    }

    private Expr parseSubExpr(int minPrec) {
        Expr left;
        UnaryOp unary = unaryOp(peek().kind());
        if (unary != null) {
            int line = advance().line();
            Expr rhs = parseSubExpr(UNARY_PRIORITY);
            left = new UnaryExpr(unary, rhs, line);
        } else {
            left = parseSimpleExpr();
        }
        while (true) {
            LuaToken tok = peek();
            BinaryOp op = binaryOp(tok.kind());
            if (op == null) break;
            int leftPrio = leftPriority(op);
            if (leftPrio <= minPrec) break;
            advance();
            Expr right = parseSubExpr(rightPriority(op));
            left = new BinaryExpr(op, left, right, tok.line());
        }
        return left;
    }

    private Expr parseSimpleExpr() {
        LuaToken t = peek();
        return switch (t.kind()) {
            case NUMBER -> { advance(); yield new LiteralExpr(LuaValue.of(t.number())); }
            case STRING -> { advance(); yield new LiteralExpr(LuaValue.of(t.lexeme())); }
            case NIL -> { advance(); yield new LiteralExpr(LuaValue.NIL); }
            case TRUE -> { advance(); yield new LiteralExpr(LuaValue.TRUE); }
            case FALSE -> { advance(); yield new LiteralExpr(LuaValue.FALSE); }
            case DOTS -> { advance(); yield new VarargExpr(t.line()); }
            case LBRACE -> parseTableConstructor();
            case FUNCTION -> { advance(); yield parseFuncBody("anonymous@" + t.line(), false, t.line()); }
            default -> parseSuffixedExpr();
        };
    }

    private Expr parseSuffixedExpr() {
        Expr primary = parsePrimaryExpr();
        while (true) {
            LuaToken t = peek();
            switch (t.kind()) {
                case DOT -> {
                    advance();
                    var name = expect(Kind.NAME, "expected field name");
                    primary = new FieldExpr(primary, new LiteralExpr(LuaValue.of(name.lexeme())), name.line());
                }
                case LBRACK -> {
                    advance();
                    Expr key = parseExpr();
                    expect(Kind.RBRACK, "expected ']'");
                    primary = new FieldExpr(primary, key, t.line());
                }
                case COLON -> {
                    advance();
                    var method = expect(Kind.NAME, "expected method name");
                    List<Expr> args = parseFuncArgs();
                    primary = new MethodCallExpr(primary, method.lexeme(), args, method.line());
                }
                case LPAREN, LBRACE, STRING -> {
                    List<Expr> args = parseFuncArgs();
                    primary = new CallExpr(primary, args, t.line());
                }
                default -> { return primary; }
            }
        }
    }

    private Expr parsePrimaryExpr() {
        LuaToken t = peek();
        if (match(Kind.LPAREN)) {
            Expr inner = parseExpr();
            expect(Kind.RPAREN, "expected ')'");
            return new ParenExpr(inner);
        }
        if (match(Kind.NAME)) return new VarExpr(t.lexeme(), t.line());
        throw error(t, "unexpected symbol near '" + t.lexeme() + "'");
    }

    private List<Expr> parseFuncArgs() {
        LuaToken t = peek();
        if (match(Kind.LPAREN)) {
            if (match(Kind.RPAREN)) return List.of();
            var args = parseExprList();
            expect(Kind.RPAREN, "expected ')'");
            return args;
        }
        if (t.kind() == Kind.LBRACE) return List.of(parseTableConstructor());
        if (match(Kind.STRING)) return List.of(new LiteralExpr(LuaValue.of(t.lexeme())));
        throw error(t, "function arguments expected");
    }

    private Expr parseTableConstructor() {
        int line = expect(Kind.LBRACE, "expected '{'").line();
        var fields = new ArrayList<TableField>();
        while (peek().kind() != Kind.RBRACE) {
            if (match(Kind.LBRACK)) {
                Expr key = parseExpr();
                expect(Kind.RBRACK, "expected ']'");
                expect(Kind.ASSIGN, "expected '='");
                fields.add(new TableField(key, parseExpr()));
            } else if (peek().kind() == Kind.NAME && lookAhead(1).kind() == Kind.ASSIGN) {
                String name = advance().lexeme();
                advance(); // '='
                fields.add(new TableField(new LiteralExpr(LuaValue.of(name)), parseExpr()));
            } else {
                fields.add(new TableField(null, parseExpr()));
            }
            if (!match(Kind.COMMA) && !match(Kind.SEMI)) break;
        }
        expect(Kind.RBRACE, "expected '}'");
        return new TableExpr(List.copyOf(fields), line);
    }

    private FunctionExpr parseFuncBody(String name, boolean hasImplicitSelf, int line) {
        expect(Kind.LPAREN, "expected '('");
        var params = new ArrayList<String>();
        if (hasImplicitSelf) params.add("self");
        boolean vararg = false;
        if (peek().kind() != Kind.RPAREN) {
            while (true) {
                if (match(Kind.DOTS)) {
                    vararg = true;
                    break;
                }
                params.add(expect(Kind.NAME, "expected parameter name").lexeme());
                if (!match(Kind.COMMA)) break;
            }
        }
        expect(Kind.RPAREN, "expected ')'");
        var body = parseBlock();
        expect(Kind.END, "to close function");
        return new FunctionExpr(name, List.copyOf(params), vararg, body, line);
    }

    private static final int UNARY_PRIORITY = 12;

    private static UnaryOp unaryOp(Kind k) {
        return switch (k) {
            case MINUS -> UnaryOp.NEG;
            case NOT -> UnaryOp.NOT;
            case HASH -> UnaryOp.LEN;
            case TILDE -> UnaryOp.BNOT;
            default -> null;
        };
    }

    private static BinaryOp binaryOp(Kind k) {
        return switch (k) {
            case PLUS -> BinaryOp.ADD;
            case MINUS -> BinaryOp.SUB;
            case STAR -> BinaryOp.MUL;
            case SLASH -> BinaryOp.DIV;
            case IDIV -> BinaryOp.IDIV;
            case PERCENT -> BinaryOp.MOD;
            case CARET -> BinaryOp.POW;
            case CONCAT -> BinaryOp.CONCAT;
            case AMP -> BinaryOp.BAND;
            case PIPE -> BinaryOp.BOR;
            case TILDE -> BinaryOp.BXOR;
            case LSHIFT -> BinaryOp.SHL;
            case RSHIFT -> BinaryOp.SHR;
            case EQ -> BinaryOp.EQ;
            case NEQ -> BinaryOp.NEQ;
            case LT -> BinaryOp.LT;
            case LE -> BinaryOp.LE;
            case GT -> BinaryOp.GT;
            case GE -> BinaryOp.GE;
            case AND -> BinaryOp.AND;
            case OR -> BinaryOp.OR;
            default -> null;
        };
    }

    private static int leftPriority(BinaryOp op) {
        return switch (op) {
            case POW -> 14;
            case MUL, DIV, IDIV, MOD -> 11;
            case ADD, SUB -> 10;
            case CONCAT -> 9;
            case SHL, SHR -> 8;
            case BAND -> 7;
            case BXOR -> 6;
            case BOR -> 5;
            case EQ, NEQ, LT, LE, GT, GE -> 4;
            case AND -> 2;
            case OR -> 1;
        };
    }

    private static int rightPriority(BinaryOp op) {
        return switch (op) {
            case POW, CONCAT -> leftPriority(op) - 1;
            default -> leftPriority(op);
        };
    }

    private static int firstLine(Expr e) {
        if (e instanceof VarExpr v) return v.line();
        if (e instanceof FieldExpr f) return f.line();
        if (e instanceof CallExpr c) return c.line();
        if (e instanceof MethodCallExpr m) return m.line();
        if (e instanceof ParenExpr p) return firstLine(p.inner());
        return 1;
    }

    private void enter() {
        if (++depth > MAX_AST_DEPTH) {
            throw error(peek(), "syntax nesting limit exceeded");
        }
    }

    private void leave() { depth--; }

    private LuaToken peek() { return tokens.get(index); }
    private LuaToken lookAhead(int offset) { return tokens.get(Math.min(tokens.size() - 1, index + offset)); }
    private LuaToken previous() { return tokens.get(Math.max(0, index - 1)); }
    private LuaToken advance() { return tokens.get(index < tokens.size() - 1 ? index++ : index); }

    private boolean match(Kind k) {
        if (peek().kind() == k) {
            advance();
            return true;
        }
        return false;
    }

    private LuaToken expect(Kind k, String msg) {
        if (peek().kind() == k) return advance();
        throw error(peek(), msg + " (got '" + peek().lexeme() + "')");
    }

    private LuaException error(LuaToken tok, String msg) {
        return new LuaException(LuaException.Reason.SYNTAX, chunkName + ":" + tok.line() + ": " + msg);
    }
}
