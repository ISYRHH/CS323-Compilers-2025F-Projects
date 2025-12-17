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
import org.antlr.v4.runtime.misc.Pair;
import framework.llvm.IRBuilder;
import framework.llvm.IRType;
import framework.llvm.IRValue;
import framework.llvm.FunctionBuilder;
import framework.llvm.BasicBlockBuilder;
import framework.llvm.LLVMIcmpPredicate;

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
        // --- Begin IR generation for Project5 ---
        IRBuilder ir = new IRBuilder();

        // helper: convert framework Types to IRType
        java.util.function.Function<Type, IRType> toIRType = new java.util.function.Function<>() {
            public IRType apply(Type t) {
                if (t == null) return IRType.pointer();
                if (t instanceof Types.PrimitiveType) {
                    if (((Types.PrimitiveType) t).prettyPrint().equals("int")) return IRType.int32();
                    return IRType.int32();
                }
                if (t instanceof Types.PointerType) return IRType.pointer();
                if (t instanceof Types.ArrayType) {
                    Types.ArrayType at = (Types.ArrayType) t;
                    IRType base = apply(at.getElement());
                    return IRType.array(base, at.getLen());
                }
                if (t instanceof Types.StructType) {
                    Types.StructType st = (Types.StructType) t;
                    // ensure structure definition is emitted in IR builder when available
                    if (st.isComplete()) {
                        try {
                            java.util.List<IRType> elems = new java.util.ArrayList<>();
                            for (Type mt : st.getMemberTypes()) elems.add(apply(mt));
                            ir.defineStructure(st.getTag(), elems);
                        } catch (RuntimeException ignore) {}
                    }
                    return IRType.structure(st.getTag());
                }
                return IRType.pointer();
            }
        };

        // define global variables
        for (Map.Entry<String, Type> e : globalVars.entrySet()) {
            IRType irt = toIRType.apply(e.getValue());
            ir.defineGlobalVar(e.getKey(), irt);
        }

        // declare functions from globalFuncs map
        // But skip those which are defined in the program to avoid declare+define duplicates
        // reuse `definedFuncs` declared earlier when scanning globals
        for (GlobalDefContext g : program.globalDef()) {
            if (g.LBRACE() != null && g.RBRACE() != null) {
                definedFuncs.add(g.Identifier().getText());
            }
        }
        for (Map.Entry<String, Type> e : globalFuncs.entrySet()) {
            if (!(e.getValue() instanceof Types.FuncType)) continue;
            if (definedFuncs.contains(e.getKey())) continue; // already have definition
            Types.FuncType ft = (Types.FuncType) e.getValue();
            List<Pair<String, IRType>> args = new ArrayList<>();
            for (int i = 0; i < ft.params.size(); i++) {
                args.add(new Pair<>("p" + i, toIRType.apply(ft.params.get(i))));
            }
            ir.declareFunction(e.getKey(), toIRType.apply(ft.ret), args);
        }

        // utility to find identifier in VarDec parse node
        java.util.function.Function<ParseTree, TerminalNode> findIdent = new java.util.function.Function<>() {
            public TerminalNode apply(ParseTree p) {
                if (p instanceof VarDecContext && ((VarDecContext) p).Identifier() != null) return ((VarDecContext) p).Identifier();
                for (int i = 0; i < p.getChildCount(); i++) {
                    TerminalNode f = apply(p.getChild(i));
                    if (f != null) return f;
                }
                return null;
            }
        };

        // For each function definition, create IR
        // Local helpers to resolve types from parse nodes (mirror earlier semantic helpers)
        java.util.function.Function<SpecifierContext, Type> typeFromSpecifierLocal = new java.util.function.Function<>() {
            public Type apply(SpecifierContext spec) {
                if (spec.INT() != null) return new Types.PrimitiveType("int");
                if (spec.CHAR() != null) return new Types.PrimitiveType("char");
                if (spec.STRUCT() != null && spec.LBRACE() == null) {
                    TerminalNode id = spec.Identifier();
                    String tag = id.getText();
                    Types.StructType st = global.lookupTag(tag);
                    if (st == null) { st = new Types.StructType(tag); global.defineTag(tag, st); }
                    return st;
                }
                // For struct with brace (definition) and other complex cases, fallback to null
                return null;
            }
        };

        java.util.function.Function<ParseTree, String> findIdentLocal = new java.util.function.Function<>() {
            public String apply(ParseTree p) {
                if (p instanceof VarDecContext && ((VarDecContext)p).Identifier() != null) return ((VarDecContext)p).Identifier().getText();
                for (int i=0;i<p.getChildCount();i++) {
                    String f = apply(p.getChild(i)); if (f!=null) return f;
                }
                return null;
            }
        };

        java.util.function.BiFunction<VarDecContext, Type, Type> resolveVarDecLocal = new java.util.function.BiFunction<>() {
            public Type apply(VarDecContext ctx, Type base) {
                // find identifier node
                ParseTree node = ctx;
                ParseTree idNode = null;
                java.util.ArrayList<Object> ops = new java.util.ArrayList<>();
                // traverse down to identifier
                java.util.Deque<ParseTree> stack = new java.util.ArrayDeque<>();
                stack.push(ctx);
                while (!stack.isEmpty()) {
                    ParseTree cur = stack.pop();
                    if (cur instanceof VarDecContext && ((VarDecContext)cur).Identifier()!=null) { idNode = cur; break; }
                    for (int i=0;i<cur.getChildCount();i++) stack.push(cur.getChild(i));
                }
                // collect ops by walking from idNode up to ctx
                ParseTree p = idNode;
                while (p != null) {
                    if (p instanceof VarDecContext) {
                        VarDecContext v = (VarDecContext) p;
                        if (v.Number() != null) ops.add(Integer.parseInt(v.Number().getText()));
                        else if (v.STAR() != null) ops.add("STAR");
                    }
                    if (p == ctx) break;
                    p = p.getParent();
                }
                // build type
                Type curT = base;
                java.util.Collections.reverse(ops);
                for (Object op: ops) {
                    if (op instanceof String) curT = new Types.PointerType(curT);
                    else curT = new Types.ArrayType(curT, (Integer)op);
                }
                return curT;
            }
        };
        for (GlobalDefContext g : program.globalDef()) {
            if (g.LBRACE() == null || g.RBRACE() == null) continue; // not a function def
            String fname = g.Identifier().getText();
            Type ftype = globalFuncs.get(fname);
            Types.FuncType ft = (Types.FuncType) ftype;

            List<Pair<String, IRType>> args = new ArrayList<>();
            FuncArgsContext fa = g.funcArgs();
            if (fa != null && fa.specifier().size() > 0) {
                for (int i = 0; i < fa.specifier().size(); i++) {
                    TerminalNode id = findIdent.apply(fa.varDec(i));
                    String nm = id != null ? id.getText() : ("p" + i);
                    args.add(new Pair<>(nm, toIRType.apply(ft.params.get(i))));
                }
            }

            FunctionBuilder fb = ir.defineFunction(fname, toIRType.apply(ft.ret), args);

            // maps for local types and addresses
            Map<String, Type> localTypes = new HashMap<>();
            Map<String, IRValue> localAddrs = new HashMap<>();
            Map<String, IRType> localIRTypes = new HashMap<>();

            // populate params
            for (int i = 0; i < args.size(); i++) {
                localTypes.put(args.get(i).a, ft.params.get(i));
                localAddrs.put(args.get(i).a, fb.param(args.get(i).a));
                // remember the IRType for the parameter
                localIRTypes.put(args.get(i).a, args.get(i).b);
            }

            BasicBlockBuilder[] curRef = new BasicBlockBuilder[]{fb.rootBlock()};

            // small expression generator
            class ExprGen {
                IRValue genR(ExpressionContext ctx) {
                    if (ctx instanceof ExprIntContext) {
                        int v = Integer.parseInt(((ExprIntContext) ctx).Number().getText());
                        return IRValue.consti32(v);
                    }
                    if (ctx instanceof ExprParensContext) return genR(((ExprParensContext) ctx).expression());
                    if (ctx instanceof ExprIdContext) {
                        IRValue addr = genAddr(ctx);
                        Type t = localTypes.get(((ExprIdContext) ctx).Identifier().getText());
                        if (t == null) t = globalVars.get(((ExprIdContext) ctx).Identifier().getText());
                        IRType ty = toIRType.apply(t);
                        return curRef[0].load(addr, ty, ((ExprIdContext) ctx).Identifier().getText() + ".val");
                    }
                    if (ctx instanceof ExprCallContext) {
                        ExprCallContext cc = (ExprCallContext) ctx;
                        String callee = cc.Identifier().getText();
                        List<IRValue> argv = new ArrayList<>();
                        List<ExpressionContext> es = cc.expression();
                        if (es == null) es = new ArrayList<>();
                        for (ExpressionContext e : es) argv.add(genR(e));
                        Types.FuncType cft = (Types.FuncType) globalFuncs.get(callee);
                        IRType ret = toIRType.apply(cft.ret);
                        return curRef[0].call(ret, callee, argv, null);
                    }
                    if (ctx instanceof ExprAddSubContext) {
                        ExpressionContext lctx = ((ExprAddSubContext) ctx).expression(0);
                        ExpressionContext rctx = ((ExprAddSubContext) ctx).expression(1);
                        IRValue l = genR(lctx);
                        IRValue r = genR(rctx);
                        boolean isPlus = ((ExprAddSubContext) ctx).PLUS() != null;
                        // pointer arithmetic: pointer +/- integer -> gep
                        if (l.type().isPointer() && r.type().isInteger()) {
                            // try to deduce element type for the pointer operand from source types
                            Type pointee = null;
                            if (lctx instanceof ExprIdContext) {
                                String bn = ((ExprIdContext) lctx).Identifier().getText();
                                Type t = localTypes.get(bn);
                                if (t == null) t = globalVars.get(bn);
                                if (t instanceof Types.PointerType) pointee = ((Types.PointerType) t).getRef();
                                else if (t instanceof Types.ArrayType) pointee = ((Types.ArrayType) t).getElement();
                            }
                            IRType elty = toIRType.apply(pointee != null ? pointee : new Types.PrimitiveType("int"));
                            // l +/- r: for subtraction, if it's l - r, perform gep with negative index
                            if (isPlus) {
                                if (elty.isArray() || elty.isStructure()) return curRef[0].gep(l, elty, 0, r, null);
                                else return curRef[0].gep(l, elty, r, null);
                            } else {
                                // compute negated index: 0 - r
                                IRValue neg = curRef[0].sub(IRValue.consti32(0), r, null);
                                if (elty.isArray() || elty.isStructure()) return curRef[0].gep(l, elty, 0, neg, null);
                                else return curRef[0].gep(l, elty, neg, null);
                            }
                        }
                        if (r.type().isPointer() && l.type().isInteger()) {
                            // integer + pointer -> pointer (commute)
                            Type pointee = null;
                            if (rctx instanceof ExprIdContext) {
                                String bn = ((ExprIdContext) rctx).Identifier().getText();
                                Type t = localTypes.get(bn);
                                if (t == null) t = globalVars.get(bn);
                                if (t instanceof Types.PointerType) pointee = ((Types.PointerType) t).getRef();
                                else if (t instanceof Types.ArrayType) pointee = ((Types.ArrayType) t).getElement();
                            }
                            IRType elty = toIRType.apply(pointee != null ? pointee : new Types.PrimitiveType("int"));
                            if (isPlus) {
                                if (elty.isArray() || elty.isStructure()) return curRef[0].gep(r, elty, 0, l, null);
                                else return curRef[0].gep(r, elty, l, null);
                            } else {
                                // l - r where r is pointer and l integer: not supported, fallback to default
                            }
                        }
                        if (isPlus) return curRef[0].add(l, r, null);
                        else return curRef[0].sub(l, r, null);
                    }
                    if (ctx instanceof ExprMulDivModContext) {
                        IRValue l = genR(((ExprMulDivModContext) ctx).expression(0));
                        IRValue r = genR(((ExprMulDivModContext) ctx).expression(1));
                        if (((ExprMulDivModContext) ctx).STAR() != null) return curRef[0].mul(l, r, null);
                        if (((ExprMulDivModContext) ctx).DIV() != null) return curRef[0].div(l, r, null);
                        return curRef[0].rem(l, r, null);
                    }
                    if (ctx instanceof ExprRelContext) {
                        ExprRelContext rc = (ExprRelContext) ctx;
                        IRValue l = genR(rc.expression(0));
                        IRValue r = genR(rc.expression(1));
                        // allow pointer <cmp> 0 by converting integer 0 to a null pointer
                        if (l.type().isPointer() && r.type().isInteger() && r.llvmName().equals("0")) {
                            r = IRValue.constNull();
                        }
                        if (r.type().isPointer() && l.type().isInteger() && l.llvmName().equals("0")) {
                            l = IRValue.constNull();
                        }
                        int op = ((TerminalNode) rc.getChild(1)).getSymbol().getType();
                        LLVMIcmpPredicate pred = LLVMIcmpPredicate.Equals;
                        switch (op) {
                            case EQ: pred = LLVMIcmpPredicate.Equals; break;
                            case NEQ: pred = LLVMIcmpPredicate.NotEquals; break;
                            case LT: pred = LLVMIcmpPredicate.SignedLT; break;
                            case GT: pred = LLVMIcmpPredicate.SignedGT; break;
                            case LE: pred = LLVMIcmpPredicate.SignedLE; break;
                            case GE: pred = LLVMIcmpPredicate.SignedGE; break;
                        }
                        IRValue cmp = curRef[0].icmp(l, pred, r, null);
                        return curRef[0].zext(cmp, IRType.int32(), null);
                    }
                    if (ctx instanceof ExprArrayContext) {
                        // evaluate element value: compute address then load
                        IRValue addr = genAddr(ctx);
                        // try to determine element type (handle nested array accesses)
                        ExprArrayContext ac = (ExprArrayContext) ctx;
                        // walk to root identifier and simulate indexing
                        java.util.Deque<ExpressionContext> idxChain2 = new java.util.ArrayDeque<>();
                        ExpressionContext root2 = ac;
                        while (root2 instanceof ExprArrayContext) {
                            ExprArrayContext r = (ExprArrayContext) root2;
                            idxChain2.addFirst(r.expression(1));
                            root2 = r.expression(0);
                        }
                        Type rootType2 = null;
                        if (root2 instanceof ExprIdContext) {
                            String bn2 = ((ExprIdContext) root2).Identifier().getText();
                            rootType2 = localTypes.get(bn2);
                            if (rootType2 == null) rootType2 = globalVars.get(bn2);
                        }
                        Type elemType2 = null;
                        if (rootType2 != null) {
                            Type curT = rootType2;
                            for (ExpressionContext ignored : idxChain2) {
                                if (curT instanceof Types.PointerType) {
                                    Type ref = ((Types.PointerType) curT).getRef();
                                    if (ref instanceof Types.ArrayType) curT = ((Types.ArrayType) ref).getElement();
                                    else curT = ref;
                                } else if (curT instanceof Types.ArrayType) {
                                    curT = ((Types.ArrayType) curT).getElement();
                                } else {
                                    curT = null;
                                    break;
                                }
                            }
                            elemType2 = curT;
                        }
                        IRType elty = toIRType.apply(elemType2 != null ? elemType2 : new Types.PrimitiveType("int"));
                        return curRef[0].load(addr, elty, "elm");
                    }
                    if (ctx instanceof ExprAssignContext) {
                        ExpressionContext left = ((ExprAssignContext) ctx).expression(0);
                        ExpressionContext right = ((ExprAssignContext) ctx).expression(1);
                        IRValue rval = genR(right);
                        if (left instanceof ExprIdContext || left instanceof ExprArrayContext) {
                            IRValue addr = genAddr(left);
                            // determine lhs type
                            Type lt = null;
                            if (left instanceof ExprIdContext) {
                                lt = localTypes.get(((ExprIdContext) left).Identifier().getText());
                                if (lt == null) lt = globalVars.get(((ExprIdContext) left).Identifier().getText());
                            } else if (left instanceof ExprArrayContext) {
                                ExprArrayContext ac = (ExprArrayContext) left;
                                if (ac.expression(0) instanceof ExprIdContext) {
                                    Type bt = localTypes.get(((ExprIdContext) ac.expression(0)).Identifier().getText());
                                    if (bt == null) bt = globalVars.get(((ExprIdContext) ac.expression(0)).Identifier().getText());
                                    if (bt instanceof Types.ArrayType) lt = ((Types.ArrayType) bt).getElement();
                                    else if (bt instanceof Types.PointerType) lt = ((Types.PointerType) bt).getRef();
                                }
                            }
                            IRType lty = toIRType.apply(lt != null ? lt : new Types.PrimitiveType("int"));
                            IRValue storeVal = rval;
                            if (lty.isPointer() && rval.type().isInteger() && rval.llvmName().equals("0")) {
                                storeVal = IRValue.constNull();
                            }
                            curRef[0].store(addr, lty, storeVal);
                            return rval;
                        } else {
                            return rval;
                        }
                    }

                    // fallback: evaluate as 0
                    return IRValue.consti32(0);
                }

                // generate address for lvalue expressions
                IRValue genAddr(ExpressionContext ctx) {
                    // handle struct member access (dot/arrow)
                    if (ctx instanceof ExprDotContext) {
                        ExprDotContext dc = (ExprDotContext) ctx;
                        // address of base struct
                        IRValue baseAddr = genAddr(dc.expression());
                        // try to find struct type
                        Type bt = null;
                        if (dc.expression() instanceof ExprIdContext) {
                            String bn = ((ExprIdContext) dc.expression()).Identifier().getText();
                            bt = localTypes.get(bn);
                            if (bt == null) bt = globalVars.get(bn);
                        }
                        if (!(bt instanceof Types.StructType)) {
                            // fallback: try to obtain a reasonable IRType for the base
                            IRType baseIR = null;
                            if (dc.expression() instanceof ExprIdContext) {
                                String bn = ((ExprIdContext) dc.expression()).Identifier().getText();
                                baseIR = localIRTypes.get(bn);
                                // try to derive an IRType from source-level type if IRType is unavailable or unhelpful
                                if ((baseIR == null || baseIR.typeEquals(IRType.pointer())) && localTypes.get(bn) != null) {
                                    Type lt = localTypes.get(bn);
                                    if (lt instanceof Types.PointerType && ((Types.PointerType) lt).getRef() instanceof Types.ArrayType) {
                                        baseIR = toIRType.apply(((Types.PointerType) lt).getRef());
                                    } else {
                                        baseIR = toIRType.apply(lt);
                                    }
                                }
                                if (baseIR == null && globalVars.get(bn) != null) {
                                    Type gt = globalVars.get(bn);
                                    if (gt instanceof Types.PointerType && ((Types.PointerType) gt).getRef() instanceof Types.ArrayType) {
                                        baseIR = toIRType.apply(((Types.PointerType) gt).getRef());
                                    } else {
                                        baseIR = toIRType.apply(gt);
                                    }
                                }
                            }
                            if (baseIR == null) {
                                return curRef[0].gep(baseAddr, IRType.pointer(), 0, IRValue.consti32(0), null);
                            } else {
                                return curRef[0].gep(baseAddr, baseIR, 0, IRValue.consti32(0), null);
                            }
                        }
                        Types.StructType st = (Types.StructType) bt;
                        String memberName = dc.Identifier().getText();
                        int idx = st.getMemberIndex(memberName);
                        Type mtype = st.getMemberType(memberName);
                        IRType elty = toIRType.apply(mtype != null ? mtype : new Types.PrimitiveType("int"));
                        return curRef[0].gep(baseAddr, elty, 0, IRValue.consti32(idx), null);
                    }
                    if (ctx instanceof ExprArrowContext) {
                        ExprArrowContext ac = (ExprArrowContext) ctx;
                        // pointer expression on lhs: prefer the address and load if the
                        // element type is itself a pointer. This avoids cases where
                        // genR returned a struct value instead of a pointer.
                        IRValue baseAddr = genAddr(ac.expression());
                        // try to deduce element type of the array-like base expression
                        Type elemTypeForBase = null;
                        // if base is an array expression, walk to its root id
                        ExpressionContext tmpRoot = ac.expression();
                        java.util.Deque<ExpressionContext> idxsForBase = new java.util.ArrayDeque<>();
                        while (tmpRoot instanceof ExprArrayContext) {
                            ExprArrayContext r = (ExprArrayContext) tmpRoot;
                            idxsForBase.addFirst(r.expression(1));
                            tmpRoot = r.expression(0);
                        }
                        Type rootT = null;
                        if (tmpRoot instanceof ExprIdContext) {
                            String bn = ((ExprIdContext) tmpRoot).Identifier().getText();
                            rootT = localTypes.get(bn);
                            if (rootT == null) rootT = globalVars.get(bn);
                        }
                        if (rootT != null) {
                            Type curT = rootT;
                            for (ExpressionContext ignored : idxsForBase) {
                                if (curT instanceof Types.PointerType) {
                                    Type ref = ((Types.PointerType) curT).getRef();
                                    if (ref instanceof Types.ArrayType) curT = ((Types.ArrayType) ref).getElement();
                                    else curT = ref;
                                } else if (curT instanceof Types.ArrayType) {
                                    curT = ((Types.ArrayType) curT).getElement();
                                } else { curT = null; break; }
                            }
                            elemTypeForBase = curT;
                        }
                        IRValue basePtr;
                        if (elemTypeForBase instanceof Types.PointerType) {
                            // addr points to a pointer value; load pointer
                            basePtr = curRef[0].load(baseAddr, IRType.pointer(), "ptr.tmp");
                        } else {
                            // addr points to the struct value itself; use its address as pointer
                            basePtr = baseAddr;
                        }
                        String memberName = ac.Identifier().getText();
                        // try to resolve member index via type info if available
                        Type bt = null;
                        if (ac.expression() instanceof ExprIdContext) {
                            String bn = ((ExprIdContext) ac.expression()).Identifier().getText();
                            bt = localTypes.get(bn);
                            if (bt == null) bt = globalVars.get(bn);
                            if (bt instanceof Types.PointerType) bt = ((Types.PointerType) bt).getRef();
                        }
                        if (!(bt instanceof Types.StructType)) {
                            IRType baseIR = null;
                            // try to use previously deduced element type for the base expression
                            if (elemTypeForBase != null) {
                                Type cand = elemTypeForBase;
                                if (cand instanceof Types.PointerType) cand = ((Types.PointerType) cand).getRef();
                                if (cand != null) baseIR = toIRType.apply(cand);
                            }
                            if (baseIR == null && ac.expression() instanceof ExprIdContext) {
                                String bn = ((ExprIdContext) ac.expression()).Identifier().getText();
                                baseIR = localIRTypes.get(bn);
                                if ((baseIR == null || baseIR.typeEquals(IRType.pointer())) && localTypes.get(bn) != null) {
                                    Type lt = localTypes.get(bn);
                                    if (lt instanceof Types.PointerType && ((Types.PointerType) lt).getRef() instanceof Types.ArrayType) {
                                        baseIR = toIRType.apply(((Types.PointerType) lt).getRef());
                                    } else {
                                        baseIR = toIRType.apply(lt);
                                    }
                                }
                                if (baseIR == null && globalVars.get(bn) != null) {
                                    Type gt = globalVars.get(bn);
                                    if (gt instanceof Types.PointerType && ((Types.PointerType) gt).getRef() instanceof Types.ArrayType) {
                                        baseIR = toIRType.apply(((Types.PointerType) gt).getRef());
                                    } else {
                                        baseIR = toIRType.apply(gt);
                                    }
                                }
                            }
                            if (baseIR == null) {
                                return curRef[0].gep(basePtr, IRType.pointer(), 0, IRValue.consti32(0), null);
                            } else {
                                return curRef[0].gep(basePtr, baseIR, 0, IRValue.consti32(0), null);
                            }
                        }
                        Types.StructType st = (Types.StructType) bt;
                        int idx = st.getMemberIndex(memberName);
                        Type mtype = st.getMemberType(memberName);
                        IRType elty = toIRType.apply(mtype != null ? mtype : new Types.PrimitiveType("int"));
                        return curRef[0].gep(basePtr, elty, 0, IRValue.consti32(idx), null);
                    }
                    if (ctx instanceof ExprIdContext) {
                        String name = ((ExprIdContext) ctx).Identifier().getText();
                        IRValue addr = localAddrs.get(name);
                        if (addr != null) return addr;
                        return ir.global(name);
                    }
                    if (ctx instanceof ExprArrayContext) {
                        ExprArrayContext ac = (ExprArrayContext) ctx;
                        ExpressionContext base = ac.expression(0);
                        ExpressionContext idx = ac.expression(1);
                        // get base pointer. For nested array accesses (e.g. a[i][j])
                        // ensure we get an address (pointer) instead of a loaded value.
                        IRValue baseAddr;
                        if (base instanceof ExprIdContext || base instanceof ExprArrayContext || base instanceof ExprDotContext || base instanceof ExprArrowContext) {
                            baseAddr = genAddr(base);
                        } else {
                            baseAddr = genR(base); // base expression yields pointer/value
                        }
                        // index value
                        IRValue indexVal = genR(idx);
                        // determine element type by walking to the root identifier and
                        // simulating index operations from left-to-right.
                        Type elemType = null;
                        // collect index chain and find root base id
                        java.util.Deque<ExpressionContext> idxChain = new java.util.ArrayDeque<>();
                        ExpressionContext root = ac;
                        while (root instanceof ExprArrayContext) {
                            ExprArrayContext r = (ExprArrayContext) root;
                            idxChain.addFirst(r.expression(1));
                            root = r.expression(0);
                        }
                        Type rootType = null;
                        if (root instanceof ExprIdContext) {
                            String bn = ((ExprIdContext) root).Identifier().getText();
                            rootType = localTypes.get(bn);
                            if (rootType == null) rootType = globalVars.get(bn);
                        } else if (root instanceof ExprDotContext) {
                            ExprDotContext dc = (ExprDotContext) root;
                            // base.struct.member: find base type then member type
                            if (dc.expression() instanceof ExprIdContext) {
                                String bn = ((ExprIdContext) dc.expression()).Identifier().getText();
                                Type bt = localTypes.get(bn);
                                if (bt == null) bt = globalVars.get(bn);
                                if (bt instanceof Types.StructType) {
                                    Types.StructType st = (Types.StructType) bt;
                                    rootType = st.getMemberType(dc.Identifier().getText());
                                }
                            }
                        } else if (root instanceof ExprArrowContext) {
                            ExprArrowContext arc = (ExprArrowContext) root;
                            // pointer->member: find base pointer type then member type
                            if (ac.expression() instanceof ExprIdContext) {
                                String bn = ((ExprIdContext) ac.expression()).Identifier().getText();
                                Type bt = localTypes.get(bn);
                                if (bt == null) bt = globalVars.get(bn);
                                if (bt instanceof Types.PointerType) bt = ((Types.PointerType) bt).getRef();
                                if (bt instanceof Types.StructType) {
                                    Types.StructType st = (Types.StructType) bt;
                                    rootType = st.getMemberType(arc.Identifier().getText());
                                }
                            }
                        }
                            if (rootType != null) {
                            Type curT = rootType;
                            for (ExpressionContext ignored : idxChain) {
                                if (curT instanceof Types.PointerType) {
                                    Type ref = ((Types.PointerType) curT).getRef();
                                    if (ref instanceof Types.ArrayType) curT = ((Types.ArrayType) ref).getElement();
                                    else curT = ref;
                                } else if (curT instanceof Types.ArrayType) {
                                    curT = ((Types.ArrayType) curT).getElement();
                                } else {
                                    curT = null;
                                    break;
                                }
                            }
                            elemType = curT;
                            // debug: show root and deduced element type
                            try {
                                    String rname = (root instanceof ExprIdContext) ? ((ExprIdContext) root).Identifier().getText() : root.getText();
                                    String rtCls = rootType != null ? rootType.getClass().getName() : "null";
                                    String etCls = elemType != null ? elemType.getClass().getName() : "null";
                                    // debug information removed
                            } catch (Exception ignore) {}
                        }
                        IRType eltyElem = toIRType.apply(elemType != null ? elemType : new Types.PrimitiveType("int"));
                        // If the base is a pointer-typed variable (stored in an alloca), load the pointer value
                        IRValue gepBase = baseAddr;
                        if (base instanceof ExprIdContext) {
                            String bname = ((ExprIdContext) base).Identifier().getText();
                            Type btype = localTypes.get(bname);
                            if (btype == null) btype = globalVars.get(bname);
                            if (btype instanceof Types.PointerType) {
                                gepBase = curRef[0].load(baseAddr, IRType.pointer(), "ptr.tmp");
                            }
                        }
                        if (!gepBase.type().isPointer()) {
                            // previously logged debug info removed
                        }
                        // decide whether the GEP should use a leading 0 (for arrays/structs)
                        // or be a single-index GEP (for pointer-to-element like int*).
                        Type arrayPointee = null;
                        if (rootType instanceof Types.ArrayType) {
                            arrayPointee = rootType;
                        } else if (rootType instanceof Types.PointerType) {
                            Type ref = ((Types.PointerType) rootType).getRef();
                            if (ref instanceof Types.ArrayType) arrayPointee = ref;
                        }
                        if (arrayPointee != null) {
                            // pointer points to an array; use two-index GEP and pass the array type
                            IRType eltyGep = toIRType.apply(arrayPointee);
                            return curRef[0].gep(gepBase, eltyGep, 0, indexVal, null);
                        } else {
                            // pointer-to-element or unknown: single-index GEP with element type
                            return curRef[0].gep(gepBase, eltyElem, indexVal, null);
                        }
                    }
                    // fallback: evaluate and assume it's a pointer already
                    return genR(ctx);
                }
            }

            ExprGen eg = new ExprGen();

            // helper to visit nested statements with current generator
            class StmtGen {
                void visitStatement(StatementContext st) {
                    if (st instanceof BlockStmtContext) {
                        for (StatementContext ss : ((BlockStmtContext) st).statement()) visitStatement(ss);
                    } else if (st instanceof ExpressionStmtContext) {
                        if (((ExpressionStmtContext) st).expression() != null) eg.genR(((ExpressionStmtContext) st).expression());
                    } else if (st instanceof VarDecStmtContext) {
                        VarDecStmtContext v = (VarDecStmtContext) st;
                            TerminalNode id = findIdent.apply(v.varDec());
                            String name = id != null ? id.getText() : "_v" + localAddrs.size();
                            Type t = resolveVarDecLocal.apply(v.varDec(), typeFromSpecifierLocal.apply(v.specifier()));
                            localTypes.put(name, t);
                            IRType irt = toIRType.apply(t);
                            IRValue addr = fb.rootBlock().alloca(irt, name + ".addr");
                            localAddrs.put(name, addr);
                            localIRTypes.put(name, irt);
                            if (v.ASSIGN() != null) {
                                IRValue rv = eg.genR(v.expression());
                                IRValue storeVal = rv;
                                if (irt.isPointer() && rv.type().isInteger() && rv.llvmName().equals("0")) {
                                    storeVal = IRValue.constNull();
                                }
                                curRef[0].store(addr, irt, storeVal);
                            }
                    } else if (st instanceof IfStmtContext) {
                        IfStmtContext is = (IfStmtContext) st;
                        IRValue condv = eg.genR(is.expression());
                        IRValue zero = condv.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                        IRValue cond = curRef[0].icmp(condv, LLVMIcmpPredicate.NotEquals, zero, null);
                        BasicBlockBuilder thenB = fb.newBasicBlock("then");
                        BasicBlockBuilder elseB = fb.newBasicBlock("else");
                        BasicBlockBuilder mergeB = fb.newBasicBlock("ifend");
                        curRef[0].condBr(cond, thenB, elseB);
                        curRef[0] = thenB;
                        visitStatement(is.statement(0));
                        if (!curRef[0].hasTerminated()) curRef[0].br(mergeB);
                        curRef[0] = elseB;
                        if (is.statement().size() > 1) visitStatement(is.statement(1));
                        if (!curRef[0].hasTerminated()) curRef[0].br(mergeB);
                        curRef[0] = mergeB;
                    } else if (st instanceof WhileStmtContext) {
                        WhileStmtContext ws = (WhileStmtContext) st;
                        BasicBlockBuilder condB = fb.newBasicBlock("while.cond");
                        BasicBlockBuilder bodyB = fb.newBasicBlock("while.body");
                        BasicBlockBuilder afterB = fb.newBasicBlock("while.end");
                        curRef[0].br(condB);
                        curRef[0] = condB;
                        IRValue condv = eg.genR(ws.expression());
                        IRValue zero = condv.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                        IRValue cond = curRef[0].icmp(condv, LLVMIcmpPredicate.NotEquals, zero, null);
                        curRef[0].condBr(cond, bodyB, afterB);
                        curRef[0] = bodyB;
                        visitStatement(ws.statement());
                        if (!curRef[0].hasTerminated()) curRef[0].br(condB);
                        curRef[0] = afterB;
                    } else if (st instanceof ReturnStmtContext) {
                        ReturnStmtContext rs = (ReturnStmtContext) st;
                        IRValue rv = eg.genR(rs.expression());
                        curRef[0].ret(rv);
                    } else {
                        // fallback: expression
                        if (st instanceof ExpressionStmtContext) {
                            ExpressionStmtContext es = (ExpressionStmtContext) st;
                            if (es.expression() != null) eg.genR(es.expression());
                        }
                    }
                }
            }
            StmtGen stg = new StmtGen();

            // walk statements
            for (StatementContext s : g.statement()) {
                if (s instanceof VarDecStmtContext) {
                    VarDecStmtContext v = (VarDecStmtContext) s;
                    // determine name
                    TerminalNode id = findIdent.apply(v.varDec());
                    String name = id != null ? id.getText() : "_v" + localAddrs.size();
                    // determine declared type from varDec
                    Type t = resolveVarDecLocal.apply(v.varDec(), typeFromSpecifierLocal.apply(v.specifier()));
                    localTypes.put(name, t);
                    IRType irt = toIRType.apply(t);
                    IRValue addr = fb.rootBlock().alloca(irt, name + ".addr");
                    localAddrs.put(name, addr);
                    localIRTypes.put(name, irt);
                        if (v.ASSIGN() != null) {
                        IRValue rv = eg.genR(v.expression());
                            IRValue storeVal = rv;
                            if (irt.isPointer() && rv.type().isInteger() && rv.llvmName().equals("0")) {
                                storeVal = IRValue.constNull();
                            }
                            curRef[0].store(addr, irt, storeVal);
                    }
                } else if (s instanceof ExpressionStmtContext) {
                    ExpressionStmtContext es = (ExpressionStmtContext) s;
                    if (es.expression() != null) eg.genR(es.expression());
                } else if (s instanceof IfStmtContext) {
                    IfStmtContext is = (IfStmtContext) s;
                    IRValue condv = eg.genR(is.expression());
                    IRValue zero = condv.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                    IRValue cond = curRef[0].icmp(condv, LLVMIcmpPredicate.NotEquals, zero, null);
                    BasicBlockBuilder thenB = fb.newBasicBlock("then");
                    BasicBlockBuilder elseB = fb.newBasicBlock("else");
                    BasicBlockBuilder mergeB = fb.newBasicBlock("ifend");
                    curRef[0].condBr(cond, thenB, elseB);
                    // then
                    curRef[0] = thenB;
                    stg.visitStatement(is.statement(0));
                    if (!curRef[0].hasTerminated()) curRef[0].br(mergeB);
                    // else
                    curRef[0] = elseB;
                    if (is.statement().size() > 1) stg.visitStatement(is.statement(1));
                    if (!curRef[0].hasTerminated()) curRef[0].br(mergeB);
                    curRef[0] = mergeB;
                } else if (s instanceof WhileStmtContext) {
                    WhileStmtContext ws = (WhileStmtContext) s;
                    BasicBlockBuilder condB = fb.newBasicBlock("while.cond");
                    BasicBlockBuilder bodyB = fb.newBasicBlock("while.body");
                    BasicBlockBuilder afterB = fb.newBasicBlock("while.end");
                    curRef[0].br(condB);
                    curRef[0] = condB;
                    IRValue condv = eg.genR(ws.expression());
                    IRValue zero = condv.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                    IRValue cond = curRef[0].icmp(condv, LLVMIcmpPredicate.NotEquals, zero, null);
                    curRef[0].condBr(cond, bodyB, afterB);
                    curRef[0] = bodyB;
                    stg.visitStatement(ws.statement());
                    if (!curRef[0].hasTerminated()) curRef[0].br(condB);
                    curRef[0] = afterB;
                } else if (s instanceof ReturnStmtContext) {
                    ReturnStmtContext rs = (ReturnStmtContext) s;
                    IRValue rv = eg.genR(rs.expression());
                    curRef[0].ret(rv);
                } else {
                    // other statements: fallback
                }
            }

            

            // Ensure function has terminating return (assumption: functions always return)
            if (!fb.rootBlock().hasTerminated()) {
                // try to add a default return 0
                fb.rootBlock().ret(IRValue.consti32(0));
            }
        }

        // print the IR
        grader.printIR(ir);
        // --- End IR generation ---
    }
}