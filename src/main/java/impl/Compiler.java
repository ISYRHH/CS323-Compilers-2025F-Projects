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
import framework.llvm.IRBuilder;
import framework.llvm.IRType;
import framework.llvm.IRValue;
import framework.llvm.FunctionBuilder;
import framework.llvm.BasicBlockBuilder;
import framework.llvm.LLVMIcmpPredicate;
import org.antlr.v4.runtime.misc.Pair;

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

    private static class VarDecl { String name; Type type; TerminalNode idTok; }

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

        // Begin IR generation
        IRBuilder ir = new IRBuilder();

        // helper to map our Types -> IRType
        Map<String, Types.StructType> tags = global.getTags();

        java.util.function.Function<Type, IRType> mapType = new java.util.function.Function<>() {
            private IRType map(Type t) {
                if (t instanceof Types.PrimitiveType) return IRType.int32();
                if (t instanceof Types.PointerType) return IRType.pointer();
                if (t instanceof Types.ArrayType) {
                    Types.ArrayType at = (Types.ArrayType) t;
                    IRType inner = map(at.getElement());
                    return IRType.array(inner, at.getLen());
                }
                if (t instanceof Types.StructType) {
                    Types.StructType st = (Types.StructType) t;
                    return IRType.structure(st.getTag());
                }
                return IRType.pointer();
            }

            @Override
            public IRType apply(Type type) { return map(type); }
        };

        // define structures
        for (Map.Entry<String, Types.StructType> e : tags.entrySet()) {
            Types.StructType st = e.getValue();
            if (!st.isComplete()) continue;
            List<IRType> members = new ArrayList<>();
            for (Types.StructType.Member m : st.getMembers()) members.add(mapType.apply(m.type));
            ir.defineStructure(st.getTag(), members);
        }

        // define globals
        for (Map.Entry<String, Type> e : globalVars.entrySet()) {
            ir.defineGlobalVar(e.getKey(), mapType.apply(e.getValue()));
        }

        // declare runtime functions
        List<Pair<String, IRType>> args0 = List.of();
        ir.declareFunction("readint", IRType.int32(), args0);
        ir.declareFunction("getrand", IRType.int32(), args0);
        ir.declareFunction("setseed", IRType.int32(), List.of(new Pair<>("v0", IRType.int32())));
        ir.declareFunction("writeint", IRType.int32(), List.of(new Pair<>("v0", IRType.int32())));
        ir.declareFunction("assert_eq", IRType.int32(), List.of(new Pair<>("a", IRType.int32()), new Pair<>("b", IRType.int32()), new Pair<>("c", IRType.int32())));

        // declare functions (only those without definitions)
        for (Map.Entry<String, Type> e : globalFuncs.entrySet()) {
            String fname = e.getKey();
            if (definedFuncs.contains(fname)) continue;
            Types.FuncType ft = (Types.FuncType) e.getValue();
            IRType ret = mapType.apply(ft.ret);
            List<Pair<String, IRType>> params = new ArrayList<>();
            for (int i = 0; i < ft.params.size(); i++) params.add(new Pair<>("p" + i, mapType.apply(ft.params.get(i))));
            ir.declareFunction(fname, ret, params);
        }

        // A compact IR emitter that supports core expressions and statements (incrementally extensible).
        // Wrap helpers and the emitter into a local helper class so methods are not declared inside a method.
        class IRGenHelpers {
            // helper primitives and local helpers for IR emitter
            final Types.PrimitiveType ITP = new Types.PrimitiveType("int");

            TerminalNode findIdentifierLocal(ParseTree p) {
                if (p instanceof VarDecContext && ((VarDecContext)p).Identifier() != null) return ((VarDecContext)p).Identifier();
                for (int i=0;i<p.getChildCount();i++) {
                    TerminalNode f = findIdentifierLocal(p.getChild(i)); if (f!=null) return f;
                }
                return null;
            }

            Type typeFromSpecifierLocal(SpecifierContext spec) {
                if (spec.INT()!=null) return new Types.PrimitiveType("int");
                if (spec.CHAR()!=null) return new Types.PrimitiveType("char");
                if (spec.STRUCT()!=null && spec.LBRACE()==null) {
                    String tag = spec.Identifier().getText();
                    Types.StructType st = global.lookupTag(tag);
                    if (st==null) { st = new Types.StructType(tag); global.defineTag(tag, st); }
                    return st;
                }
                // fallback
                return new Types.PrimitiveType("int");
            }

            VarDecl resolveVarDecLocal(VarDecContext ctx, Type base) {
                TerminalNode idTok = findIdentifierLocal(ctx);
                VarDecl r = new VarDecl(); r.name = idTok.getText(); r.idTok = idTok; List<Object> ops = new ArrayList<>();
                ParseTree node = idTok.getParent();
                while (node!=null) {
                    if (node instanceof VarDecContext) {
                        VarDecContext v = (VarDecContext) node;
                        if (v.Number()!=null) ops.add(Integer.parseInt(v.Number().getText()));
                        else if (v.STAR()!=null) ops.add("STAR");
                    }
                    if (node==ctx) break; node = node.getParent();
                }
                Type curType = base; Collections.reverse(ops);
                for (Object op : ops) { if (op instanceof String) curType = new Types.PointerType(curType); else curType = new Types.ArrayType(curType, (Integer)op); }
                r.type = curType; return r;
            }

            class IREmitter extends SplcBaseVisitor<Void> {
            FunctionBuilder fb;
            BasicBlockBuilder cur;
            Deque<Map<String, IRValue>> env = new ArrayDeque<>();
            Deque<Map<String, Type>> envTy = new ArrayDeque<>();

            void pushEnv() { env.push(new HashMap<>()); envTy.push(new HashMap<>()); }
            void popEnv() { env.pop(); envTy.pop(); }
            void envDefine(String name, IRValue addr, Type ty) { env.peek().put(name, addr); envTy.peek().put(name, ty); }
            IRValue envLookupAddr(String name) {
                for (Map<String, IRValue> m : env) if (m.containsKey(name)) return m.get(name);
                return ir.global(name);
            }
            Type envLookupType(String name) {
                for (Map<String, Type> m : envTy) if (m.containsKey(name)) return m.get(name);
                return global.lookup(name);
            }

            class ERes { IRValue val; Type type; boolean isLvalue; IRValue addr; }

            ERes eval(ExpressionContext ctx) {
                if (ctx instanceof ExprIntContext) {
                    ERes r = new ERes(); r.val = IRValue.consti32(Integer.parseInt(((ExprIntContext) ctx).Number().getText())); r.type = ITP; r.isLvalue = false; return r;
                }
                if (ctx instanceof ExprParensContext) return eval(((ExprParensContext) ctx).expression());
                if (ctx instanceof ExprIdContext) {
                    String name = ((ExprIdContext) ctx).Identifier().getText();
                    ERes r = new ERes(); r.type = envLookupType(name); r.isLvalue = true; r.addr = envLookupAddr(name); r.val = r.addr; return r;
                }
                if (ctx instanceof ExprCallContext) {
                    ExprCallContext c = (ExprCallContext) ctx; String fname = c.Identifier().getText();
                    Types.FuncType ft = (Types.FuncType) global.lookup(fname);
                    List<IRValue> args = new ArrayList<>();
                    List<ExpressionContext> aexprs = c.expression(); if (aexprs == null) aexprs = new ArrayList<>();
                    for (ExpressionContext ac : aexprs) {
                        ERes e = eval(ac);
                        if (e.isLvalue) { e.val = cur.load(e.addr, mapType.apply(e.type), null); e.isLvalue = false; }
                        args.add(e.val);
                    }
                    IRType rett = mapType.apply(ft.ret);
                    IRValue callv = cur.call(rett, fname, args, null);
                    ERes r = new ERes(); r.val = callv; r.type = ft.ret; r.isLvalue = false; return r;
                }
                if (ctx instanceof ExprAddSubContext) {
                    ExprAddSubContext c = (ExprAddSubContext) ctx;
                    ERes L = eval(c.expression(0)); ERes R = eval(c.expression(1));
                    if (L.isLvalue) L.val = cur.load(L.addr, mapType.apply(L.type), null);
                    if (R.isLvalue) R.val = cur.load(R.addr, mapType.apply(R.type), null);
                    // int + int
                    if (Types.isEqual(L.type, ITP) && Types.isEqual(R.type, ITP)) {
                        ERes o = new ERes(); o.type = ITP; o.isLvalue = false; o.val = (c.PLUS()!=null) ? cur.add(L.val, R.val, null) : cur.sub(L.val, R.val, null); return o;
                    }
                    // pointer +/- int
                    if (L.type instanceof Types.PointerType && Types.isEqual(R.type, ITP)) {
                        Types.PointerType pt = (Types.PointerType) L.type;
                        Type ref = pt.getRef();
                        IRValue idx = R.val;
                        if (c.MINUS()!=null) idx = cur.sub(IRValue.consti32(0), R.val, null);
                        IRValue gep = cur.gep(L.val, mapType.apply(ref), idx, null);
                        ERes o = new ERes(); o.type = L.type; o.isLvalue = false; o.val = gep; return o;
                    }
                    if (R.type instanceof Types.PointerType && Types.isEqual(L.type, ITP)) {
                        Types.PointerType pt = (Types.PointerType) R.type;
                        Type ref = pt.getRef();
                        IRValue idx = L.val;
                        if (c.MINUS()!=null) { // int - ptr not supported
                            throw new RuntimeException("unsupported int - pointer");
                        }
                        IRValue gep = cur.gep(R.val, mapType.apply(ref), idx, null);
                        ERes o = new ERes(); o.type = R.type; o.isLvalue = false; o.val = gep; return o;
                    }
                    throw new RuntimeException("unsupported add/sub");
                }
                if (ctx instanceof ExprMulDivModContext) {
                    ExprMulDivModContext c = (ExprMulDivModContext) ctx;
                    ERes L = eval(c.expression(0)); ERes R = eval(c.expression(1));
                    if (L.isLvalue) L.val = cur.load(L.addr, mapType.apply(L.type), null);
                    if (R.isLvalue) R.val = cur.load(R.addr, mapType.apply(R.type), null);
                    ERes o = new ERes(); o.type = ITP; o.isLvalue = false;
                    if (c.DIV()!=null) o.val = cur.div(L.val, R.val, null);
                    else if (c.MOD()!=null) o.val = cur.rem(L.val, R.val, null);
                    else o.val = cur.mul(L.val, R.val, null);
                    return o;
                }
                if (ctx instanceof ExprRelContext) {
                    ExprRelContext c = (ExprRelContext) ctx;
                    ERes L = eval(c.expression(0)); ERes R = eval(c.expression(1));
                    if (L.isLvalue) L.val = cur.load(L.addr, mapType.apply(L.type), null);
                    if (R.isLvalue) R.val = cur.load(R.addr, mapType.apply(R.type), null);
                    int op = ((TerminalNode)c.getChild(1)).getSymbol().getType();
                    if (op == EQ || op == NEQ) {
                        // allow pointer == 0 comparisons: convert integer zero to null pointer if needed
                        LLVMIcmpPredicate pred = (op == EQ) ? LLVMIcmpPredicate.Equals : LLVMIcmpPredicate.NotEquals;
                        IRValue left = L.val;
                        IRValue right = R.val;
                        if (left.type().isPointer() && right.type().isInteger()) right = IRValue.constNull();
                        else if (right.type().isPointer() && left.type().isInteger()) left = IRValue.constNull();
                        IRValue cmp = cur.icmp(left, pred, right, null);
                        IRValue z = cur.zext(cmp, IRType.int32(), null);
                        ERes o = new ERes(); o.val = z; o.type = ITP; o.isLvalue = false; return o;
                    } else {
                        LLVMIcmpPredicate pred = switch (op) {
                            case LT -> LLVMIcmpPredicate.SignedLT;
                            case LE -> LLVMIcmpPredicate.SignedLE;
                            case GT -> LLVMIcmpPredicate.SignedGT;
                            case GE -> LLVMIcmpPredicate.SignedGE;
                            default -> throw new RuntimeException("unknown rel");
                        };
                        IRValue cmp = cur.icmp(L.val, pred, R.val, null);
                        IRValue z = cur.zext(cmp, IRType.int32(), null);
                        ERes o = new ERes(); o.val = z; o.type = ITP; o.isLvalue = false; return o;
                    }
                }
                if (ctx instanceof ExprAndContext) {
                    ExprAndContext c = (ExprAndContext) ctx;
                    ERes L = eval(c.expression(0)); if (L.isLvalue) L.val = cur.load(L.addr, mapType.apply(L.type), null);
                    IRValue zeroL = L.val.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                    IRValue condL = cur.icmp(L.val, LLVMIcmpPredicate.NotEquals, zeroL, null);
                    BasicBlockBuilder thenB = fb.newBasicBlock("and.rhs");
                    BasicBlockBuilder elseB = fb.newBasicBlock("and.false");
                    BasicBlockBuilder contB = fb.newBasicBlock("and.end");
                    // allocate temp in root for merging result
                    IRValue resAddr = fb.rootBlock().alloca(IRType.int32(), "and.tmp");
                    cur.condBr(condL, thenB, elseB);
                    // then: evaluate rhs
                    cur = thenB;
                    ERes R = eval(c.expression(1)); if (R.isLvalue) R.val = cur.load(R.addr, mapType.apply(R.type), null);
                    IRValue zeroR = R.val.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                    IRValue rcond = cur.icmp(R.val, LLVMIcmpPredicate.NotEquals, zeroR, null);
                    IRValue rz = cur.zext(rcond, IRType.int32(), null);
                    cur.store(resAddr, IRType.int32(), rz);
                    if (!cur.hasTerminated()) cur.br(contB);
                    // else: false
                    cur = elseB;
                    cur.store(resAddr, IRType.int32(), IRValue.consti32(0));
                    if (!cur.hasTerminated()) cur.br(contB);
                    // cont
                    cur = contB;
                    IRValue finalv = cur.load(resAddr, IRType.int32(), null);
                    ERes o = new ERes(); o.val = finalv; o.type = ITP; o.isLvalue = false; return o;
                }
                if (ctx instanceof ExprOrContext) {
                    ExprOrContext c = (ExprOrContext) ctx;
                    ERes L = eval(c.expression(0)); if (L.isLvalue) L.val = cur.load(L.addr, mapType.apply(L.type), null);
                    IRValue zeroL = L.val.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                    IRValue condL = cur.icmp(L.val, LLVMIcmpPredicate.NotEquals, zeroL, null);
                    BasicBlockBuilder thenB = fb.newBasicBlock("or.true");
                    BasicBlockBuilder elseB = fb.newBasicBlock("or.rhs");
                    BasicBlockBuilder contB = fb.newBasicBlock("or.end");
                    IRValue resAddr = fb.rootBlock().alloca(IRType.int32(), "or.tmp");
                    cur.condBr(condL, thenB, elseB);
                    // then: true
                    cur = thenB;
                    cur.store(resAddr, IRType.int32(), IRValue.consti32(1));
                    if (!cur.hasTerminated()) cur.br(contB);
                    // else: evaluate rhs
                    cur = elseB;
                    ERes R = eval(c.expression(1)); if (R.isLvalue) R.val = cur.load(R.addr, mapType.apply(R.type), null);
                    IRValue zeroR = R.val.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                    IRValue rcond = cur.icmp(R.val, LLVMIcmpPredicate.NotEquals, zeroR, null);
                    IRValue rz = cur.zext(rcond, IRType.int32(), null);
                    cur.store(resAddr, IRType.int32(), rz);
                    if (!cur.hasTerminated()) cur.br(contB);
                    cur = contB;
                    IRValue finalv = cur.load(resAddr, IRType.int32(), null);
                    ERes o = new ERes(); o.val = finalv; o.type = ITP; o.isLvalue = false; return o;
                }
                if (ctx instanceof ExprPrefixContext) {
                    ExprPrefixContext c = (ExprPrefixContext) ctx; int op = ((TerminalNode)c.getChild(0)).getSymbol().getType();
                    ERes e = eval(c.expression()); if (op==PLUS || op==MINUS) {
                        if (e.isLvalue) e.val = cur.load(e.addr, IRType.int32(), null);
                        ERes o = new ERes(); o.type = ITP; o.isLvalue = false; o.val = (op==PLUS) ? cur.add(e.val, IRValue.consti32(0), null) : cur.sub(IRValue.consti32(0), e.val, null); return o;
                    }
                    if (op==STAR) {
                        if (!(e.type instanceof Types.PointerType)) throw new RuntimeException("dereference of non-pointer");
                        IRValue base = e.isLvalue ? cur.load(e.addr, mapType.apply(e.type), null) : e.val;
                        Type ref = ((Types.PointerType)e.type).getRef();
                        ERes r = new ERes(); r.addr = base; r.val = base; r.type = ref; r.isLvalue = true; return r;
                    }
                    if (op==AMP) {
                        if (!e.isLvalue) throw new RuntimeException("& requires lvalue");
                        Type pty = new Types.PointerType(e.type);
                        ERes r = new ERes(); r.addr = null; r.val = e.addr; r.type = pty; r.isLvalue = false; return r;
                    }
                    if (op==NOT) {
                        if (e.isLvalue) e.val = cur.load(e.addr, IRType.int32(), null);
                        IRValue cmp = cur.icmp(e.val, LLVMIcmpPredicate.Equals, IRValue.consti32(0), null);
                        IRValue z = cur.zext(cmp, IRType.int32(), null);
                        ERes o = new ERes(); o.val = z; o.type = ITP; o.isLvalue = false; return o;
                    }
                    if (op==INC || op==DEC) {
                        if (!e.isLvalue) throw new RuntimeException("++/-- requires lvalue");
                        if (Types.isEqual(e.type, ITP)) {
                            IRValue curv = cur.load(e.addr, IRType.int32(), null);
                            IRValue one = IRValue.consti32(1);
                            IRValue newv = (op==INC) ? cur.add(curv, one, null) : cur.sub(curv, one, null);
                            cur.store(e.addr, IRType.int32(), newv);
                            ERes o = new ERes(); o.val = newv; o.type = ITP; o.isLvalue = false; return o;
                        }
                        if (e.type instanceof Types.PointerType) {
                            Types.PointerType pt = (Types.PointerType) e.type;
                            Type ref = pt.getRef();
                            IRValue curp = cur.load(e.addr, mapType.apply(e.type), null);
                            IRValue delta = IRValue.consti32(op==INC ? 1 : -1);
                            IRValue newp = cur.gep(curp, mapType.apply(ref), delta, null);
                            cur.store(e.addr, mapType.apply(e.type), newp);
                            ERes o = new ERes(); o.val = newp; o.type = e.type; o.isLvalue = false; return o;
                        }
                        throw new RuntimeException("unsupported ++/-- on type " + e.type);
                    }
                }
                if (ctx instanceof ExprPostfixContext) {
                    ExprPostfixContext c = (ExprPostfixContext) ctx;
                    ERes e = eval(c.expression());
                    int op = ((TerminalNode)c.getChild(1)).getSymbol().getType();
                    if (!e.isLvalue) throw new RuntimeException("postfix ++/-- requires lvalue");
                    if (Types.isEqual(e.type, ITP)) {
                        IRValue oldv = cur.load(e.addr, IRType.int32(), null);
                        IRValue one = IRValue.consti32(1);
                        IRValue newv = (op==INC) ? cur.add(oldv, one, null) : cur.sub(oldv, one, null);
                        cur.store(e.addr, IRType.int32(), newv);
                        ERes o = new ERes(); o.val = oldv; o.type = ITP; o.isLvalue = false; return o;
                    }
                    if (e.type instanceof Types.PointerType) {
                        IRValue oldp = cur.load(e.addr, mapType.apply(e.type), null);
                        Types.PointerType pt = (Types.PointerType) e.type;
                        Type ref = pt.getRef();
                        IRValue delta = IRValue.consti32(op==INC ? 1 : -1);
                        IRValue newp = cur.gep(oldp, mapType.apply(ref), delta, null);
                        cur.store(e.addr, mapType.apply(e.type), newp);
                        ERes o = new ERes(); o.val = oldp; o.type = e.type; o.isLvalue = false; return o;
                    }
                    throw new RuntimeException("unsupported postfix ++/-- on type " + e.type);
                }
                if (ctx instanceof ExprAssignContext) {
                    ExprAssignContext c = (ExprAssignContext) ctx;
                    ERes L = eval(c.expression(0)); ERes R = eval(c.expression(1));
                    if (!L.isLvalue) throw new RuntimeException("assign to non-lvalue");
                    if (R.isLvalue) R.val = cur.load(R.addr, mapType.apply(R.type), null);
                    IRValue storeVal = R.val;
                    if (L.type instanceof Types.PointerType && storeVal.type().isInteger()) storeVal = IRValue.constNull();
                    cur.store(L.addr, mapType.apply(L.type), storeVal);
                    ERes o = new ERes(); o.val = R.val; o.type = R.type; o.isLvalue = false; return o;
                }
                if (ctx instanceof ExprArrayContext) {
                    ExprArrayContext c = (ExprArrayContext) ctx;
                    ERes L = eval(c.expression(0));
                    ERes I = eval(c.expression(1));
                    if (I.isLvalue) I.val = cur.load(I.addr, IRType.int32(), null);
                    IRValue idx = I.val;
                    IRValue basePtr;
                    if (L.isLvalue) {
                        // If L is a pointer lvalue, load the pointer value; if it's an array lvalue, use the address.
                        if (L.type instanceof Types.PointerType) basePtr = cur.load(L.addr, mapType.apply(L.type), null);
                        else basePtr = L.addr;
                    } else basePtr = L.val;

                    if (L.type instanceof Types.ArrayType) {
                        Types.ArrayType at = (Types.ArrayType) L.type;
                        Type elem = at.getElement();
                        // for arrays, gep must use the array type as the inbounds type
                        IRValue gep = cur.gep(basePtr, mapType.apply(L.type), 0, idx, null);
                        ERes r = new ERes(); r.addr = gep; r.val = gep; r.type = elem; r.isLvalue = true; return r;
                    } else if (L.type instanceof Types.PointerType) {
                        Types.PointerType pt = (Types.PointerType) L.type;
                        Type elem = pt.getRef();
                        IRValue gep = cur.gep(basePtr, mapType.apply(elem), idx, null);
                        ERes r = new ERes(); r.addr = gep; r.val = gep; r.type = elem; r.isLvalue = true; return r;
                    } else {
                        throw new RuntimeException("unsupported array indexing on type " + L.type);
                    }
                }
                if (ctx instanceof ExprDotContext) {
                    ExprDotContext c = (ExprDotContext) ctx;
                    ERes L = eval(c.expression());
                    IRValue basePtr = L.isLvalue ? L.addr : L.val;
                    if (!(L.type instanceof Types.StructType)) throw new RuntimeException("dot on non-struct");
                    Types.StructType st = (Types.StructType) L.type;
                    String mem = c.Identifier().getText();
                    List<Types.StructType.Member> members = st.getMembers();
                    int idx = 0; boolean found = false;
                    for (int i=0;i<members.size();i++) { if (members.get(i).name.equals(mem)) { idx = i; found = true; break; } }
                    if (!found) throw new RuntimeException("unknown member " + mem);
                    Type mtype = st.getMemberType(mem);
                    IRValue gep = cur.gep(basePtr, mapType.apply(L.type), 0, IRValue.consti32(idx), null);
                    ERes r = new ERes(); r.addr = gep; r.val = gep; r.type = mtype; r.isLvalue = true; return r;
                }
                if (ctx instanceof ExprArrowContext) {
                    ExprArrowContext c = (ExprArrowContext) ctx;
                    ERes L = eval(c.expression());
                    if (L.isLvalue) L.val = cur.load(L.addr, mapType.apply(L.type), null);
                    if (!(L.type instanceof Types.PointerType)) throw new RuntimeException("arrow on non-pointer");
                    Types.PointerType pt = (Types.PointerType) L.type;
                    if (!(pt.getRef() instanceof Types.StructType)) throw new RuntimeException("arrow on non-struct-pointer");
                    Types.StructType st = (Types.StructType) pt.getRef();
                    String mem = c.Identifier().getText();
                    List<Types.StructType.Member> members = st.getMembers();
                    int idx = 0; boolean found = false;
                    for (int i=0;i<members.size();i++) { if (members.get(i).name.equals(mem)) { idx = i; found = true; break; } }
                    if (!found) throw new RuntimeException("unknown member " + mem);
                    Type mtype = st.getMemberType(mem);
                    IRValue gep = cur.gep(L.val, mapType.apply(pt.getRef()), 0, IRValue.consti32(idx), null);
                    ERes r = new ERes(); r.addr = gep; r.val = gep; r.type = mtype; r.isLvalue = true; return r;
                }
                throw new RuntimeException("unhandled expr: " + ctx.getText());
            }

            // statements
            @Override public Void visitBlockStmt(BlockStmtContext ctx) {
                pushEnv();
                for (StatementContext s : ctx.statement()) visit(s);
                popEnv();
                return null;
            }
            @Override public Void visitVarDecStmt(VarDecStmtContext ctx) {
                VarDecl vd = resolveVarDecLocal(ctx.varDec(), typeFromSpecifierLocal(ctx.specifier()));
                IRType ty = mapType.apply(vd.type);
                IRValue addr = fb.rootBlock().alloca(ty, vd.name + ".addr");
                envDefine(vd.name, addr, vd.type);
                if (ctx.ASSIGN()!=null) {
                    ERes v = eval(ctx.expression());
                    if (v.isLvalue) {
                        // If assigning an array to a pointer, decay array->pointer to first element
                        if (vd.type instanceof Types.PointerType && v.type instanceof Types.ArrayType) {
                            IRValue basePtr = v.addr; // pointer to array
                            Types.ArrayType at = (Types.ArrayType) v.type;
                            IRValue gep = cur.gep(basePtr, mapType.apply(v.type), 0, IRValue.consti32(0), null);
                            v.val = gep;
                        } else {
                            v.val = cur.load(v.addr, mapType.apply(v.type), null);
                        }
                    }
                    IRValue storeVal = v.val;
                    if (vd.type instanceof Types.PointerType && storeVal.type().isInteger()) storeVal = IRValue.constNull();
                    cur.store(addr, ty, storeVal);
                }
                return null;
            }

            @Override public Void visitExpressionStmt(ExpressionStmtContext ctx) { eval(ctx.expression()); return null; }

                @Override public Void visitIfStmt(IfStmtContext ctx) {
                ERes c = eval(ctx.expression()); if (c.isLvalue) c.val = cur.load(c.addr, mapType.apply(c.type), null);
                IRValue zeroCond = c.val.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                IRValue condi = cur.icmp(c.val, LLVMIcmpPredicate.NotEquals, zeroCond, null);
                BasicBlockBuilder thenB = fb.newBasicBlock("then");
                BasicBlockBuilder elseB = fb.newBasicBlock("else");
                BasicBlockBuilder contB = fb.newBasicBlock("ifend");
                cur.condBr(condi, thenB, elseB);
                cur = thenB; visit(ctx.statement(0)); if (!cur.hasTerminated()) cur.br(contB);
                cur = elseB; if (ctx.statement().size()>1) visit(ctx.statement(1)); if (!cur.hasTerminated()) cur.br(contB);
                cur = contB; return null;
            }

            @Override public Void visitWhileStmt(WhileStmtContext ctx) {
                BasicBlockBuilder condB = fb.newBasicBlock("while.cond");
                BasicBlockBuilder bodyB = fb.newBasicBlock("while.body");
                BasicBlockBuilder endB = fb.newBasicBlock("while.end");
                cur.br(condB);
                cur = condB; ERes c = eval(ctx.expression()); if (c.isLvalue) c.val = cur.load(c.addr, mapType.apply(c.type), null);
                IRValue zeroCond = c.val.type().isPointer() ? IRValue.constNull() : IRValue.consti32(0);
                IRValue condi = cur.icmp(c.val, LLVMIcmpPredicate.NotEquals, zeroCond, null);
                cur.condBr(condi, bodyB, endB);
                cur = bodyB; visit(ctx.statement()); if (!cur.hasTerminated()) cur.br(condB);
                cur = endB; return null;
            }

            @Override public Void visitReturnStmt(ReturnStmtContext ctx) {
                ERes e = eval(ctx.expression()); if (e.isLvalue) e.val = cur.load(e.addr, mapType.apply(e.type), null);
                cur.ret(e.val); return null;
            }

            // emit a function body from GlobalDefContext
            public void emitFunction(GlobalDefContext g) {
                String fname = g.Identifier().getText();
                Types.FuncType ft = (Types.FuncType) global.lookup(fname);
                IRType rett = mapType.apply(ft.ret);
                List<Pair<String, IRType>> params = new ArrayList<>();
                for (int i = 0; i < ft.params.size(); i++) params.add(new Pair<>("p"+i, mapType.apply(ft.params.get(i))));
                fb = ir.defineFunction(fname, rett, params);
                cur = fb.rootBlock();
                pushEnv();
                // bind parameters by declared names
                FuncArgsContext fa = g.funcArgs();
                if (fa!=null) {
                    for (int i=0;i<fa.specifier().size();i++) {
                        VarDecl vd = resolveVarDecLocal(fa.varDec(i), typeFromSpecifierLocal(fa.specifier(i)));
                        IRValue paddr = fb.param(i);
                        envDefine(vd.name, paddr, vd.type);
                    }
                }
                for (StatementContext s : g.statement()) visit(s);
                if (!cur.hasTerminated()) { if (fb.getReturnType().isInteger()) cur.ret(IRValue.consti32(0)); else cur.ret(IRValue.constNull()); }
                popEnv();
            }
        }

        }

        IRGenHelpers irh = new IRGenHelpers();

        // emit bodies for defined functions
        for (GlobalDefContext g : program.globalDef()) {
            if (g.LBRACE()!=null && g.RBRACE()!=null && definedFuncs.contains(g.Identifier().getText())) {
                irh.new IREmitter().emitFunction(g);
            }
        }

        grader.printIR(ir);
    }
}