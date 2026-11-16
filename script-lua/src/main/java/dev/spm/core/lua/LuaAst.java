package dev.spm.core.lua;

import java.util.List;

/** Immutable Lua AST statements, assignment targets, and expressions. */
final class LuaAst {
    private LuaAst() {}

    record Chunk(String name, List<Stmt> statements) {}

    sealed interface Stmt permits AssignStmt, LocalStmt, LocalFuncStmt, ExprStmt, DoStmt, WhileStmt, RepeatStmt,
            IfStmt, NumericForStmt, GenericForStmt, BreakStmt, ReturnStmt, EmptyStmt {}

    record EmptyStmt() implements Stmt {}
    record AssignStmt(List<LValue> targets, List<Expr> values, int line) implements Stmt {}
    record LocalStmt(List<String> names, List<Expr> values, int line) implements Stmt {}
    record LocalFuncStmt(String name, FunctionExpr function) implements Stmt {}
    record ExprStmt(Expr expr) implements Stmt {}
    record DoStmt(List<Stmt> body) implements Stmt {}
    record WhileStmt(Expr condition, List<Stmt> body, int line) implements Stmt {}
    record RepeatStmt(List<Stmt> body, Expr condition, int line) implements Stmt {}
    record IfBranch(Expr condition, List<Stmt> body, int line) {}
    record IfStmt(List<IfBranch> branches, List<Stmt> elseBody) implements Stmt {}
    record NumericForStmt(String var, Expr start, Expr limit, Expr step, List<Stmt> body, int line) implements Stmt {}
    record GenericForStmt(List<String> vars, List<Expr> iterators, List<Stmt> body, int line) implements Stmt {}
    record BreakStmt(int line) implements Stmt {}
    record ReturnStmt(List<Expr> values, int line) implements Stmt {}

    sealed interface LValue permits VarTarget, IndexTarget {}
    record VarTarget(String name, int line) implements LValue {}
    record IndexTarget(Expr table, Expr key, int line) implements LValue {}

    enum UnaryOp { NEG, NOT, LEN, BNOT }
    enum BinaryOp {
        ADD, SUB, MUL, DIV, IDIV, MOD, POW, CONCAT,
        BAND, BOR, BXOR, SHL, SHR,
        EQ, NEQ, LT, LE, GT, GE,
        AND, OR
    }

    sealed interface Expr permits LiteralExpr, VarargExpr, VarExpr, FieldExpr, TableExpr,
            FunctionExpr, UnaryExpr, BinaryExpr, CallExpr, MethodCallExpr, ParenExpr {}

    record LiteralExpr(LuaValue value) implements Expr {}
    record VarargExpr(int line) implements Expr {}
    record VarExpr(String name, int line) implements Expr {}
    record FieldExpr(Expr table, Expr key, int line) implements Expr {}
    record TableField(Expr key, Expr value) {}
    record TableExpr(List<TableField> fields, int line) implements Expr {}
    record FunctionExpr(String debugName, List<String> params, boolean hasVararg, List<Stmt> body, int line) implements Expr {}
    record UnaryExpr(UnaryOp op, Expr right, int line) implements Expr {}
    record BinaryExpr(BinaryOp op, Expr left, Expr right, int line) implements Expr {}
    record CallExpr(Expr callee, List<Expr> args, int line) implements Expr {}
    record MethodCallExpr(Expr target, String method, List<Expr> args, int line) implements Expr {}
    /** Explicit parentheses force multi-return expressions to adjust to a single value. */
    record ParenExpr(Expr inner) implements Expr {}
}
