package impl;

import framework.AbstractCompiler;
import framework.AbstractGrader;
import framework.lang.Type;
import framework.project3.Project3SemanticError;
import framework.project4.Project4Exception;
import framework.project4.Project4SemanticError;
import generated.Splc.SplcBaseVisitor;
import generated.Splc.SplcLexer;
import generated.Splc.SplcParser;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.IOException;
import java.util.*;

import static generated.Splc.SplcParser.*;

public class Compiler extends AbstractCompiler {
    public Compiler(AbstractGrader grader) {
        super(grader);
    }

    static class ValueInfo {
        Type type;
        boolean isLvalue;

        public ValueInfo(Type type, boolean isLvalue) {
            this.type = type;
            this.isLvalue = isLvalue;
        }
    }

    @Override
    public void start() throws IOException {
        CharStream input = CharStreams.fromStream(this.grader.getSourceStream());
        SplcLexer lexer = new SplcLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SplcParser parser = new SplcParser(tokens);

        ProgramContext program = parser.program();

        Scope global = new Scope(null);
        Map<String, Type> globalVars = new LinkedHashMap<>();
        Map<String, TerminalNode> globalVarTokens = new LinkedHashMap<>();
        Map<String, Type> globalFuncs = new LinkedHashMap<>();
        Set<String> definedFuncs = new HashSet<>();

        new SplcBaseVisitor<Void>() {
            private Scope cur = global;
            private final Types.PrimitiveType INT = new Types.PrimitiveType("int");
            private final Types.PrimitiveType CHAR = new Types.PrimitiveType("char");
            private Deque<Types.StructType> definingStack = new ArrayDeque<>();
            private Type currentFuncRetType = null;
            private void checkExpr(ExpressionContext ctx) {
                if (ctx == null) return;
                try {
                    new ExprVisitor(cur).visit(ctx);
                } catch (Project4Exception e) {
                    grader.reportSemanticError(e);
                }
            }

            private boolean isZero(ExpressionContext ctx) {
                if (ctx instanceof ExprIntContext) {
                    return ctx.getText().equals("0");
                }
                if (ctx instanceof ExprParensContext) {
                    return isZero(((ExprParensContext) ctx).expression());
                }
                return false;
            }

            private boolean isValidTypeForBinary(Type t) {
                return Types.isEqual(t, INT) || (t instanceof Types.PointerType);
            }


            class ExprVisitor extends SplcBaseVisitor<ValueInfo> {
                private final Scope scope;

                ExprVisitor(Scope scope) {
                    this.scope = scope;
                }

                @Override
                public ValueInfo visitExprId(ExprIdContext ctx) {
                    String name = ctx.Identifier().getText();
                    Type t = scope.lookup(name);
                    if (t == null) return new ValueInfo(INT, true); // Fallback
                    if (t instanceof Types.FuncType) {
                        Project4SemanticError.identifierNotVariable(ctx, name).throwException();
                    }
                    return new ValueInfo(t, true);
                }

                @Override
                public ValueInfo visitExprInt(ExprIntContext ctx) {
                    return new ValueInfo(INT, false);
                }

                @Override
                public ValueInfo visitExprParens(ExprParensContext ctx) {
                    return visit(ctx.expression());
                }

                @Override
                public ValueInfo visitExprCall(ExprCallContext ctx) {
                    String funcName = ctx.Identifier().getText();
                    Type t = scope.lookup(funcName);
                    if (!(t instanceof Types.FuncType)) {
                        Project4SemanticError.identifierNotFunction(ctx, funcName).throwException();
                    }
                    Types.FuncType ft = (Types.FuncType) t;
                    List<ExpressionContext> args = ctx.expression();
                    if (args == null) args = new ArrayList<>();

                    if (args.size() != ft.params.size()) {
                        Project4SemanticError.badParamCount(ctx, ft.params.size(), args.size()).throwException();
                    }
                    for (int i = 0; i < args.size(); i++) {
                        ValueInfo argVal = visit(args.get(i));
                        if (!Types.isEqual(ft.params.get(i), argVal.type)) {
                            Project4SemanticError.badParamType(ctx, i + 1).throwException();
                        }
                    }
                    return new ValueInfo(ft.ret, false);
                }

                @Override
                public ValueInfo visitExprArray(ExprArrayContext ctx) {
                    ValueInfo lhs = visit(ctx.expression(0));
                    boolean isArray = lhs.type instanceof Types.ArrayType;
                    boolean isPtr = lhs.type instanceof Types.PointerType;

                    if (!isArray && !isPtr) {
                        Project4SemanticError.unexpectedType(ctx, lhs.type).throwException();
                    }
                    if (isArray && !lhs.isLvalue) {
                        Project4SemanticError.lvalueRequired(ctx).throwException();
                    }

                    ValueInfo rhs = visit(ctx.expression(1));
                    if (!Types.isEqual(rhs.type, INT)) {
                        Project4SemanticError.unexpectedType(ctx, rhs.type).throwException();
                    }

                    Type retType = isArray ? ((Types.ArrayType) lhs.type).getElement() : ((Types.PointerType) lhs.type).getRef();
                    return new ValueInfo(retType, true);
                }

                @Override
                public ValueInfo visitExprDot(ExprDotContext ctx) {
                    ValueInfo lhs = visit(ctx.expression());
                    if (!(lhs.type instanceof Types.StructType)) {
                        Project4SemanticError.unexpectedType(ctx, lhs.type).throwException();
                    }
                    if (!lhs.isLvalue) {
                        Project4SemanticError.lvalueRequired(ctx).throwException();
                    }
                    Types.StructType st = (Types.StructType) lhs.type;
                    String memberName = ctx.Identifier().getText();
                    if (!st.hasMember(memberName)) {
                        Project4SemanticError.badMember(ctx, st, memberName).throwException();
                    }
                    return new ValueInfo(st.getMemberType(memberName), true);
                }

                @Override
                public ValueInfo visitExprArrow(ExprArrowContext ctx) {
                    ValueInfo lhs = visit(ctx.expression());
                    if (!(lhs.type instanceof Types.PointerType)) {
                        Project4SemanticError.unexpectedType(ctx, lhs.type).throwException();
                    }
                    Types.PointerType pt = (Types.PointerType) lhs.type;
                    if (!(pt.getRef() instanceof Types.StructType)) {
                        Project4SemanticError.unexpectedType(ctx, lhs.type).throwException();
                    }
                    Types.StructType st = (Types.StructType) pt.getRef();
                    String memberName = ctx.Identifier().getText();
                    if (!st.hasMember(memberName)) {
                        Project4SemanticError.badMember(ctx, st, memberName).throwException();
                    }
                    return new ValueInfo(st.getMemberType(memberName), true);
                }

                @Override
                public ValueInfo visitExprPrefix(ExprPrefixContext ctx) {
                    int op = ((TerminalNode) ctx.getChild(0)).getSymbol().getType();
                    ValueInfo expr = visit(ctx.expression());
                    switch (op) {
                        case INC: case DEC:
                            if (!expr.isLvalue) Project4SemanticError.lvalueRequired(ctx).throwException();
                            if (!Types.isEqual(expr.type, INT) && !(expr.type instanceof Types.PointerType)) {
                                Project4SemanticError.unexpectedType(ctx, expr.type).throwException();
                            }
                            return new ValueInfo(expr.type, false);
                        case PLUS: case MINUS: case NOT:
                            if (op == NOT) {
                                if (!Types.isEqual(expr.type, INT) && !(expr.type instanceof Types.PointerType)) {
                                    Project4SemanticError.unexpectedType(ctx, expr.type).throwException();
                                }
                                return new ValueInfo(INT, false);
                            } else {
                                if (!Types.isEqual(expr.type, INT)) {
                                    Project4SemanticError.unexpectedType(ctx, expr.type).throwException();
                                }
                                return new ValueInfo(INT, false);
                            }
                        case STAR:
                            if (!(expr.type instanceof Types.PointerType)) {
                                Project4SemanticError.unexpectedType(ctx, expr.type).throwException();
                            }
                            return new ValueInfo(((Types.PointerType) expr.type).getRef(), true);
                        case AMP:
                            if (!expr.isLvalue) {
                                Project4SemanticError.lvalueRequired(ctx).throwException();
                            }
                            return new ValueInfo(new Types.PointerType(expr.type), false);
                    }
                    return null;
                }

                @Override
                public ValueInfo visitExprPostfix(ExprPostfixContext ctx) {
                    ValueInfo expr = visit(ctx.expression());
                    if (!expr.isLvalue) Project4SemanticError.lvalueRequired(ctx).throwException();
                    if (!(Types.isEqual(expr.type, INT)) && !(expr.type instanceof Types.PointerType)) {
                        Project4SemanticError.unexpectedType(ctx, expr.type).throwException();
                    }
                    return new ValueInfo(expr.type, false);
                }

                @Override
                public ValueInfo visitExprMulDivMod(ExprMulDivModContext ctx) {
                    ValueInfo lhs = visit(ctx.expression(0));
                    ValueInfo rhs = visit(ctx.expression(1));
                    if (!Types.isEqual(lhs.type, INT)) Project4SemanticError.unexpectedType(ctx, lhs.type).throwException();
                    if (!Types.isEqual(rhs.type, INT)) Project4SemanticError.unexpectedType(ctx, rhs.type).throwException();
                    return new ValueInfo(INT, false);
                }

                @Override
                public ValueInfo visitExprAddSub(ExprAddSubContext ctx) {
                    ValueInfo lhs = visit(ctx.expression(0));
                    ValueInfo rhs = visit(ctx.expression(1));
                    int op = 0;

                    if (ctx.PLUS() != null) {
                        op = ctx.PLUS().getSymbol().getType();
                    } else if (ctx.MINUS() != null) {
                        op = ctx.MINUS().getSymbol().getType();
                    }

                    boolean lhsInt = Types.isEqual(lhs.type, INT);
                    boolean rhsInt = Types.isEqual(rhs.type, INT);
                    boolean lhsPtr = lhs.type instanceof Types.PointerType;
                    boolean rhsPtr = rhs.type instanceof Types.PointerType;

                    if (op == PLUS) {
                        if (lhsInt && rhsInt) return new ValueInfo(INT, false);
                        if (lhsPtr && rhsInt) return new ValueInfo(lhs.type, false);
                        if (lhsInt && rhsPtr) return new ValueInfo(rhs.type, false);
                        if (lhsPtr && rhsPtr) Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ((TerminalNode) ctx.getChild(1)).getSymbol(), lhs.type, rhs.type).throwException();
                    } else {
                        if (lhsInt && rhsInt) return new ValueInfo(INT, false);
                        if (lhsPtr && rhsInt) return new ValueInfo(lhs.type, false);
                        if (lhsPtr && rhsPtr) {
                            if (!Types.isEqual(lhs.type, rhs.type)) {
                                Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ((TerminalNode) ctx.getChild(1)).getSymbol(), lhs.type, rhs.type).throwException();
                            }
                            return new ValueInfo(INT, false);
                        }
                    }
                    // Catch-all for types not Int or Pointer (e.g. Array + Int, Struct + Struct)
                    if (!(lhs.type instanceof Types.PrimitiveType || lhs.type instanceof Types.PointerType))
                        Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ((TerminalNode) ctx.getChild(1)).getSymbol(), lhs.type, rhs.type).throwException();
                    if (!(rhs.type instanceof Types.PrimitiveType || rhs.type instanceof Types.PointerType))
                        Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ((TerminalNode) ctx.getChild(1)).getSymbol(), lhs.type, rhs.type).throwException();

                    Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ((TerminalNode) ctx.getChild(1)).getSymbol(), lhs.type, rhs.type).throwException();
                    return null;
                }

                @Override
                public ValueInfo visitExprRel(ExprRelContext ctx) {
                    ValueInfo lhs = visit(ctx.expression(0));
                    ValueInfo rhs = visit(ctx.expression(1));
                    int op = ((TerminalNode) ctx.getChild(1)).getSymbol().getType();

                    if (op == EQ || op == NEQ) {
                        boolean match = Types.isEqual(lhs.type, rhs.type);
                        if (!match) {
                            boolean lhsPtr = lhs.type instanceof Types.PointerType;
                            boolean rhsPtr = rhs.type instanceof Types.PointerType;
                            if (lhsPtr && isZero(ctx.expression(1))) match = true;
                            else if (rhsPtr && isZero(ctx.expression(0))) match = true;
                        }

                        boolean validTypes = isValidTypeForBinary(lhs.type) && isValidTypeForBinary(rhs.type);
                        if (match) {
                            boolean oneIsPtr = lhs.type instanceof Types.PointerType || rhs.type instanceof Types.PointerType;
                            boolean oneIsZero = isZero(ctx.expression(0)) || isZero(ctx.expression(1));
                            if (oneIsPtr && oneIsZero) {
                                // valid
                            } else {
                                if (!isValidTypeForBinary(lhs.type)) {
                                    Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ((TerminalNode) ctx.getChild(1)).getSymbol(), lhs.type, rhs.type).throwException();
                                }
                            }
                        } else {
                            Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ((TerminalNode) ctx.getChild(1)).getSymbol(), lhs.type, rhs.type).throwException();
                        }
                    } else {
                        if (!Types.isEqual(lhs.type, INT)) Project4SemanticError.unexpectedType(ctx, lhs.type).throwException();
                        if (!Types.isEqual(rhs.type, INT)) Project4SemanticError.unexpectedType(ctx, rhs.type).throwException();
                    }
                    return new ValueInfo(INT, false);
                }

                @Override
                public ValueInfo visitExprAnd(ExprAndContext ctx) { return checkLogic(ctx, ctx.expression(0), ctx.expression(1)); }
                @Override
                public ValueInfo visitExprOr(ExprOrContext ctx) { return checkLogic(ctx, ctx.expression(0), ctx.expression(1)); }

                private ValueInfo checkLogic(ExpressionContext ctx, ExpressionContext e1, ExpressionContext e2) {
                    ValueInfo v1 = visit(e1);
                    ValueInfo v2 = visit(e2);
                    if (!isValidTypeForBinary(v1.type)) Project4SemanticError.unexpectedType(ctx, v1.type).throwException();
                    if (!isValidTypeForBinary(v2.type)) Project4SemanticError.unexpectedType(ctx, v2.type).throwException();
                    return new ValueInfo(INT, false);
                }

                @Override
                public ValueInfo visitExprAssign(ExprAssignContext ctx) {
                    ValueInfo lhs = visit(ctx.expression(0));
                    if (!lhs.isLvalue) Project4SemanticError.lvalueRequired(ctx).throwException();

                    ValueInfo rhs = visit(ctx.expression(1));

                    boolean match = Types.isEqual(lhs.type, rhs.type);
                    if (!match) {
                        if (lhs.type instanceof Types.PointerType && isZero(ctx.expression(1))) {
                            match = true;
                        }
                    }

                    if (match) {
                        if (!isValidTypeForBinary(lhs.type)) {
                            Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ctx.ASSIGN().getSymbol(), lhs.type, rhs.type).throwException();
                        }
                    } else {
                        Project4SemanticError.unmatchedTypeForBinaryOP(ctx, ctx.ASSIGN().getSymbol(), lhs.type, rhs.type).throwException();
                    }

                    // Assignment expression's value should be the right-hand side's value.
                    return new ValueInfo(rhs.type, false);
                }
            }

            private Type typeFromSpecifier(SpecifierContext spec) {
                if (spec.INT() != null) return INT;
                if (spec.CHAR() != null) return CHAR;
                if (spec.STRUCT() != null && spec.LBRACE() == null) {
                    TerminalNode id = spec.Identifier();
                    String tag = id.getText();
                    Types.StructType st = cur.lookupTag(tag);
                    if (st == null) { st = new Types.StructType(tag); cur.defineTag(tag, st); }
                    return st;
                }
                if (spec.STRUCT() != null && spec.LBRACE() != null) {
                    TerminalNode id = spec.Identifier();
                    String tag = id.getText();
                    Types.StructType existing = cur.lookupTag(tag);
                    // P3 redecl checks...
                    if (existing != null && definingStack.contains(existing) && cur.hasTagHere(tag)) grader.reportSemanticError(Project3SemanticError.redeclaration(id));
                    if (existing != null && existing.isComplete() && cur.hasTagHere(tag)) grader.reportSemanticError(Project3SemanticError.redeclaration(id));
                    Types.StructType st = (existing != null && cur.hasTagHere(tag)) ? existing : new Types.StructType(tag);
                    cur.defineTag(tag, st);
                    definingStack.push(st);
                    try {
                        List<SpecifierContext> specs = spec.specifier();
                        List<VarDecContext> vds = spec.varDec();
                        for (int i = 0; i < specs.size(); i++) {
                            Type base = typeFromSpecifier(specs.get(i));
                            VarDecl resolved = resolveVarDec(vds.get(i), base);
                            if (resolved.type instanceof Types.StructType && !((Types.StructType)resolved.type).isComplete()) grader.reportSemanticError(Project3SemanticError.memberIncomplete(resolved.idTok));
                            if (st.hasMember(resolved.name)) grader.reportSemanticError(Project3SemanticError.memberDuplicate(resolved.idTok));
                            st.addMember(resolved.name, resolved.type);
                        }
                        st.setComplete(true);
                    } finally { definingStack.pop(); }
                    return st;
                }
                return null;
            }

            private class VarDecl { String name; Type type; TerminalNode idTok; }
            private VarDecl resolveVarDec(VarDecContext ctx, Type base) {
                TerminalNode idTok = findIdentifier(ctx);
                VarDecl r = new VarDecl();
                r.name = idTok.getText();
                r.idTok = idTok;
                List<Object> ops = new ArrayList<>();
                ParseTree node = idTok.getParent();
                while (node != null) {
                    if (node instanceof VarDecContext) {
                        VarDecContext v = (VarDecContext) node;
                        if (v.Number() != null) ops.add(Integer.parseInt(v.Number().getText()));
                        else if (v.STAR() != null) ops.add("STAR");
                    }
                    if (node == ctx) break;
                    node = node.getParent();
                }
                Type curType = base;
                Collections.reverse(ops);
                for (Object op : ops) {
                    if (op instanceof String) curType = new Types.PointerType(curType);
                    else curType = new Types.ArrayType(curType, (Integer) op);
                }
                r.type = curType;
                return r;
            }
            private TerminalNode findIdentifier(ParseTree p) {
                if (p instanceof VarDecContext && ((VarDecContext)p).Identifier() != null) return ((VarDecContext)p).Identifier();
                for (int i=0; i<p.getChildCount(); i++) {
                    TerminalNode f = findIdentifier(p.getChild(i)); if (f!=null) return f;
                }
                return null;
            }

            @Override
            public Void visitProgram(ProgramContext ctx) {
                for (GlobalDefContext g : ctx.globalDef()) {
                    if (g.LBRACE() != null && g.RBRACE() != null) { // Func Def
                        String fname = g.Identifier().getText();
                        Type rett = typeFromSpecifier(g.specifier());
                        Types.FuncType ft = new Types.FuncType(rett);
                        FuncArgsContext fa = g.funcArgs();
                        if (fa != null && fa.specifier().size() > 0) {
                            for (int i = 0; i < fa.specifier().size(); i++) {
                                Type pt = typeFromSpecifier(fa.specifier(i));
                                VarDecl vd = resolveVarDec(fa.varDec(i), pt);
                                ft.addParam(vd.type);
                            }
                        }
                        if (global.containsHere(fname)) {
                            Type existing = global.lookup(fname);
                            if (existing instanceof Types.FuncType && !definedFuncs.contains(fname)) {
                                ft = (Types.FuncType) existing;
                            } else {
                                grader.reportSemanticError(Project3SemanticError.redefinition(g.Identifier()));
                            }
                        } else {
                            global.define(fname, ft);
                            globalFuncs.putIfAbsent(fname, ft);
                        }
                        definedFuncs.add(fname);
                        Scope old = cur;
                        cur = new Scope(global);
                        currentFuncRetType = ft.ret;
                        if (fa != null && fa.specifier().size() > 0) {
                            for (int i = 0; i < fa.specifier().size(); i++) {
                                Type pt = typeFromSpecifier(fa.specifier(i));
                                VarDecl vd = resolveVarDec(fa.varDec(i), pt);
                                if (cur.containsHere(vd.name)) grader.reportSemanticError(Project3SemanticError.redefinition(vd.idTok));
                                cur.define(vd.name, vd.type);
                            }
                        }
                        for (StatementContext s : g.statement()) visit(s);
                        currentFuncRetType = null;
                        cur = old;
                    } else if (g.funcArgs() != null) { // Func Decl
                        String fname = g.Identifier().getText();
                        Type rett = typeFromSpecifier(g.specifier());
                        Types.FuncType ft = new Types.FuncType(rett);
                        FuncArgsContext fa = g.funcArgs();
                        if (fa != null) {
                            Set<String> pn = new HashSet<>();
                            for (int i=0; i<fa.specifier().size(); i++) {
                                VarDecl vd = resolveVarDec(fa.varDec(i), typeFromSpecifier(fa.specifier(i)));
                                if (pn.contains(vd.name)) grader.reportSemanticError(Project3SemanticError.redefinition(vd.idTok));
                                pn.add(vd.name); ft.addParam(vd.type);
                            }
                        }
                        if (global.containsHere(fname)) grader.reportSemanticError(Project3SemanticError.redeclaration(g.Identifier()));
                        global.define(fname, ft); globalFuncs.putIfAbsent(fname, ft);
                    } else if (g.varDec() != null) {
                        VarDecl vd = resolveVarDec(g.varDec(), typeFromSpecifier(g.specifier()));
                        if (vd.type instanceof Types.ArrayType && ((Types.ArrayType) vd.type).getElement() instanceof Types.StructType && !((Types.StructType) ((Types.ArrayType) vd.type).getElement()).isComplete()) grader.reportSemanticError(Project3SemanticError.definitionIncomplete(vd.idTok));
                        if (global.containsHere(vd.name)) grader.reportSemanticError(Project3SemanticError.redefinition(vd.idTok));
                        if (global.lookup(vd.name)!=null) grader.reportSemanticError(Project3SemanticError.redeclaration(g.varDec().Identifier()));
                        global.define(vd.name, vd.type); globalVars.putIfAbsent(vd.name, vd.type); globalVarTokens.putIfAbsent(vd.name, vd.idTok);
                    } else {
                        SpecifierContext sc = g.specifier();
                        if (sc.STRUCT()!=null && sc.LBRACE()!=null) typeFromSpecifier(sc);
                        else if (sc.STRUCT()!=null) {
                            String t = sc.Identifier().getText();
                            if (global.lookupTag(t)==null) global.defineTag(t, new Types.StructType(t));
                        }
                    }
                }
                for (Map.Entry<String, Type> e : globalVars.entrySet()) {
                    if (e.getValue() instanceof Types.StructType && !((Types.StructType)e.getValue()).isComplete()) grader.reportSemanticError(Project3SemanticError.definitionIncomplete(globalVarTokens.get(e.getKey())));
                }
                return null;
            }

            @Override
            public Void visitVarDecStmt(VarDecStmtContext ctx) {
                VarDecl vd = resolveVarDec(ctx.varDec(), typeFromSpecifier(ctx.specifier()));
                if (cur.containsHere(vd.name)) grader.reportSemanticError(Project3SemanticError.redefinition(vd.idTok));
                if (vd.type instanceof Types.StructType && !((Types.StructType)vd.type).isComplete()) grader.reportSemanticError(Project3SemanticError.definitionIncomplete(vd.idTok));
                cur.define(vd.name, vd.type);
                if (ctx.ASSIGN() != null) {
                    try {
                        ValueInfo init = new ExprVisitor(cur).visit(ctx.expression());
                        boolean match = Types.isEqual(vd.type, init.type);
                        if (!match && vd.type instanceof Types.PointerType && isZero(ctx.expression())) match = true;

                        if (match) {
                            if (!isValidTypeForBinary(vd.type)) Project4SemanticError.unmatchedTypeForBinaryOP(ctx.expression(), ctx.ASSIGN().getSymbol(), vd.type, init.type).throwException();
                        } else {
                            Project4SemanticError.unmatchedTypeForBinaryOP(ctx.expression(), ctx.ASSIGN().getSymbol(), vd.type, init.type).throwException();
                        }
                    } catch (Project4Exception ex) { grader.reportSemanticError(ex); }
                }
                return null;
            }

            @Override
            public Void visitBlockStmt(BlockStmtContext ctx) {
                Scope old = cur; cur = new Scope(old);
                for (StatementContext s : ctx.statement()) visit(s);
                cur = old; return null;
            }

            @Override
            public Void visitExpressionStmt(ExpressionStmtContext ctx) { checkExpr(ctx.expression()); return null; }

            @Override
            public Void visitIfStmt(IfStmtContext ctx) {
                try {
                    ValueInfo v = new ExprVisitor(cur).visit(ctx.expression());
                    if (!isValidTypeForBinary(v.type)) Project4SemanticError.unexpectedType(ctx.expression(), v.type).throwException();
                } catch (Project4Exception e) { grader.reportSemanticError(e); }
                visit(ctx.statement(0)); if (ctx.statement().size()>1) visit(ctx.statement(1));
                return null;
            }

            @Override
            public Void visitWhileStmt(WhileStmtContext ctx) {
                try {
                    ValueInfo v = new ExprVisitor(cur).visit(ctx.expression());
                    if (!isValidTypeForBinary(v.type)) Project4SemanticError.unexpectedType(ctx.expression(), v.type).throwException();
                } catch (Project4Exception e) { grader.reportSemanticError(e); }
                visit(ctx.statement()); return null;
            }

            @Override
            public Void visitReturnStmt(ReturnStmtContext ctx) {
                try {
                    ValueInfo v = new ExprVisitor(cur).visit(ctx.expression());
                    if (!Types.isEqual(currentFuncRetType, v.type)) Project4SemanticError.unexpectedType(ctx.expression(), v.type).throwException();
                } catch (Project4Exception e) { grader.reportSemanticError(e); }
                return null;
            }
        }.visit(program);
    }
}