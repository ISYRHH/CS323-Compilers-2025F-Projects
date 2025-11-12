package impl;

import framework.AbstractCompiler;
import framework.AbstractGrader;
import framework.project3.Project3SemanticError;
import generated.Splc.SplcBaseVisitor;
import generated.Splc.SplcLexer;
import generated.Splc.SplcParser;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import generated.Splc.SplcParser.*;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.ParseTree;

public class Compiler extends AbstractCompiler {
    public Compiler(AbstractGrader grader) {
        super(grader);
    }

    @Override
    public void start() throws IOException {
        CharStream input = CharStreams.fromStream(this.grader.getSourceStream());
        SplcLexer lexer = new SplcLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SplcParser parser = new SplcParser(tokens);

        SplcParser.ProgramContext program = parser.program();

        // global scope
        Scope global = new Scope(null);
        // keep insertion order for printing
        Map<String, framework.lang.Type> globalVars = new LinkedHashMap<>();
        Map<String, framework.lang.Type> globalFuncs = new LinkedHashMap<>();

        // visitor to process program
        new SplcBaseVisitor<Void>() {
            private Scope cur = global;

            private Types.PrimitiveType INT = new Types.PrimitiveType("int");
            private Types.PrimitiveType CHAR = new Types.PrimitiveType("char");

            private Types.StructType ensureTag(SplcParser.SpecifierContext spec) {
                // spec: STRUCT Identifier [ ... ]
                TerminalNode id = spec.Identifier();
                String name = id.getText();
                // tag scope begins after this appearance, but for simplicity we register it here
                Types.StructType st = cur.lookupTag(name);
                if (st == null) {
                    st = new Types.StructType(name);
                    cur.defineTag(name, st);
                }
                return st;
            }

            private framework.lang.Type typeFromSpecifier(SplcParser.SpecifierContext spec) {
                if (spec.INT() != null) return INT;
                if (spec.CHAR() != null) return CHAR;
                if (spec.STRUCT() != null && spec.LBRACE() == null) {
                    // struct Identifier (maybe incomplete or reference to existing tag)
                    TerminalNode id = spec.Identifier();
                    String tag = id.getText();
                    Types.StructType st = cur.lookupTag(tag);
                    if (st == null) {
                        // declare incomplete tag in current scope
                        st = new Types.StructType(tag);
                        cur.defineTag(tag, st);
                    }
                    return st;
                }
                // struct Identifier { members }
                if (spec.STRUCT() != null && spec.LBRACE() != null) {
                    TerminalNode id = spec.Identifier();
                    String tag = id.getText();
                    // if tag exists and is complete in same scope -> redeclaration error
                    Types.StructType existing = cur.lookupTag(tag);
                    if (existing != null && existing.isComplete() && cur.hasTagHere(tag)) {
                        grader.reportSemanticError(Project3SemanticError.redeclaration(id));
                    }
                    Types.StructType st = existing != null ? existing : new Types.StructType(tag);
                    // mark visible immediately per project note
                    cur.defineTag(tag, st);
                    // fill members
                    List<SpecifierContext> specs = spec.specifier();
                    List<VarDecContext> vds = spec.varDec();
                    for (int i = 0; i < specs.size(); i++) {
                        SpecifierContext ms = specs.get(i);
                        VarDecContext mv = vds.get(i);
                        // resolve member type and name using a temporary context: use current scope for tags
                        framework.lang.Type base = typeFromSpecifier(ms);
                        VarDecl resolved = resolveVarDec(mv, base);
                        // member cannot have incomplete type (except pointer)
                        if (resolved.type instanceof Types.StructType) {
                            Types.StructType memberStruct = (Types.StructType) resolved.type;
                            if (!memberStruct.isComplete()) {
                                grader.reportSemanticError(Project3SemanticError.memberIncomplete(resolved.idTok));
                            }
                        }
                        // duplicate member names not tracked across name spaces here; but check within struct
                        st.addMember(resolved.name, resolved.type);
                    }
                    st.setComplete(true);
                    return st;
                }
                return null;
            }

            private class VarDecl { String name; framework.lang.Type type; TerminalNode idTok; }

            private VarDecl resolveVarDec(VarDecContext ctx, framework.lang.Type base) {
                Objects.requireNonNull(ctx);
                // Find the Identifier terminal inside this declarator subtree
                TerminalNode idTok = findIdentifier(ctx);
                if (idTok == null) throw new RuntimeException("no identifier in varDec");

                VarDecl r = new VarDecl();
                r.name = idTok.getText();
                r.idTok = idTok;

                // collect operators encountered when walking up from the identifier to the
                // top declarator context. We record them in the order encountered (inner->outer),
                // then reverse to apply them in correct semantic order.
                java.util.List<Object> ops = new java.util.ArrayList<>();
                ParseTree node = idTok.getParent();
                while (node != null) {
                    if (node instanceof VarDecContext) {
                        VarDecContext v = (VarDecContext) node;
                        if (v.Number() != null) {
                            ops.add(Integer.parseInt(v.Number().getText())); // array with length
                        } else if (v.STAR() != null) {
                            ops.add("STAR");
                        }
                    }
                    if (node == ctx) break;
                    node = node.getParent();
                }

                // apply ops in reverse (outermost last) to the base type
                framework.lang.Type cur = base;
                java.util.Collections.reverse(ops);
                for (Object op : ops) {
                    if (op instanceof String && ((String) op).equals("STAR")) {
                        cur = new Types.PointerType(cur);
                    } else if (op instanceof Integer) {
                        cur = new Types.ArrayType(cur, (Integer) op);
                    }
                }

                r.type = cur;
                return r;
            }

            private TerminalNode findIdentifier(ParseTree p) {
                if (p instanceof VarDecContext) {
                    VarDecContext v = (VarDecContext) p;
                    if (v.Identifier() != null) return v.Identifier();
                }
                for (int i = 0; i < p.getChildCount(); i++) {
                    ParseTree c = p.getChild(i);
                    TerminalNode found = findIdentifier(c);
                    if (found != null) return found;
                }
                return null;
            }

            // helper: when applying a prefix pointer operator to a declarator-derived type,
            // arrays bind tighter than pointer in the declarator grammar. That means when
            // parsing yields STAR(varDec) where varDec produced an ArrayType(element, len),
            // the correct semantic type is ArrayType(PointerType(element), len) (i.e. array of
            // pointer-to-element), not PointerType(ArrayType(element,len)). This helper
            // pushes the pointer constructor down into nested array element types.
            private framework.lang.Type pushPointerInside(framework.lang.Type t) {
                if (t instanceof Types.ArrayType) {
                    Types.ArrayType at = (Types.ArrayType) t;
                    return new Types.ArrayType(pushPointerInside(at.getElement()), at.getLen());
                }
                return new Types.PointerType(t);
            }

            private void checkExpr(ExpressionContext e) {
                if (e == null) return;
                // if Identifier terminal and single child
                if (e.Identifier() != null && e.getChildCount() == 1) {
                    TerminalNode id = e.Identifier();
                    String name = id.getText();
                    if (cur.lookup(name) == null) {
                        grader.reportSemanticError(Project3SemanticError.undeclaredUse(id));
                    }
                    return;
                }
                // function call: Identifier LPAREN ... RPAREN
                if (e.Identifier() != null && e.getChildCount() >= 3 && e.getChild(1).getText().equals("(")) {
                    TerminalNode id = e.Identifier();
                    if (cur.lookup(id.getText()) == null) {
                        grader.reportSemanticError(Project3SemanticError.undeclaredUse(id));
                    }
                    // check args
                    for (int i = 2; i < e.getChildCount() - 1; i++) {
                        if (e.getChild(i) instanceof ExpressionContext) {
                            checkExpr((ExpressionContext) e.getChild(i));
                        }
                        // commas are ignored
                    }
                    return;
                }
                // recurse into children expressions
                for (int i = 0; i < e.getChildCount(); i++) {
                    if (e.getChild(i) instanceof ExpressionContext) {
                        checkExpr((ExpressionContext) e.getChild(i));
                    }
                }
            }

            @Override
            public Void visitProgram(ProgramContext ctx) {
                // process global definitions in order
                for (GlobalDefContext g : ctx.globalDef()) {
                    // function definition?
                    if (g.LBRACE() != null && g.RBRACE() != null) {
                        // header: specifier Identifier LPAREN funcArgs RPAREN
                        String fname = g.Identifier().getText();
                        framework.lang.Type rett = typeFromSpecifier(g.specifier());
                        Types.FuncType ft = new Types.FuncType(rett);
                        // params
                        FuncArgsContext fa = g.funcArgs();
                        if (fa != null && fa.specifier().size() > 0) {
                            for (int i = 0; i < fa.specifier().size(); i++) {
                                framework.lang.Type pt = typeFromSpecifier(fa.specifier(i));
                                VarDecl vd = resolveVarDec(fa.varDec(i), pt);
                                ft.addParam(vd.type);
                            }
                        }
                        // redeclaration/definition checks
                        if (global.containsHere(fname)) {
                            framework.lang.Type existing = global.lookup(fname);
                            if (existing instanceof Types.FuncType) {
                                // previously declared as function: accept declaration before definition
                                // prefer the existing FuncType (so its parameter list is preserved)
                                ft = (Types.FuncType) existing;
                            } else {
                                // declared as non-function (e.g., variable) -> redeclaration
                                grader.reportSemanticError(Project3SemanticError.redeclaration(g.Identifier()));
                            }
                        } else {
                            // register function before processing body so recursive calls work
                            global.define(fname, ft);
                            globalFuncs.putIfAbsent(fname, ft);
                        }

                        // create new scope for function body
                        Scope old = cur;
                        cur = new Scope(global);
                        // add parameters into current scope
                        if (fa != null && fa.specifier().size() > 0) {
                            for (int i = 0; i < fa.specifier().size(); i++) {
                                framework.lang.Type pt = typeFromSpecifier(fa.specifier(i));
                                VarDecl vd = resolveVarDec(fa.varDec(i), pt);
                                // check duplicate param names in same function prototype / params
                                if (cur.containsHere(vd.name)) {
                                    grader.reportSemanticError(Project3SemanticError.redefinition(vd.idTok));
                                }
                                cur.define(vd.name, vd.type);
                            }
                        }
                        // visit statements in body
                        for (StatementContext s : g.statement()) {
                            visit(s);
                        }
                        cur = old;
                    }
                    // function declaration? (specifier Identifier LPAREN funcArgs RPAREN SEMI)
                    else if (g.funcArgs() != null && g.Identifier() != null && g.LBRACE() == null) {
                        String fname = g.Identifier().getText();
                        framework.lang.Type rett = typeFromSpecifier(g.specifier());
                        Types.FuncType ft = new Types.FuncType(rett);
                        FuncArgsContext fa = g.funcArgs();
                        if (fa != null && fa.specifier().size() > 0) {
                            for (int i = 0; i < fa.specifier().size(); i++) {
                                framework.lang.Type pt = typeFromSpecifier(fa.specifier(i));
                                VarDecl vd = resolveVarDec(fa.varDec(i), pt);
                                ft.addParam(vd.type);
                            }
                        }
                        // redeclaration checks: if already declared/defined in this scope -> error
                        if (global.containsHere(fname)) {
                            grader.reportSemanticError(Project3SemanticError.redeclaration(g.Identifier()));
                        }
                        global.define(fname, ft);
                        globalFuncs.putIfAbsent(fname, ft);
                    } else if (g.varDec() != null) {
                        // global variable definition: specifier varDec SEMI
                        framework.lang.Type base = typeFromSpecifier(g.specifier());
                        VarDecl vd = resolveVarDec(g.varDec(), base);
                        String name = vd.name;
                        // if element type of array must be complete: check for Array at top-level
                        if (vd.type instanceof Types.ArrayType) {
                            Types.ArrayType at = (Types.ArrayType) vd.type;
                            if (at.getElement() instanceof Types.StructType) {
                                Types.StructType st = (Types.StructType) at.getElement();
                                if (!st.isComplete()) {
                                    grader.reportSemanticError(Project3SemanticError.definitionIncomplete(vd.idTok));
                                }
                            }
                        }
                        // redefinition checks
                        if (global.containsHere(name)) {
                            grader.reportSemanticError(Project3SemanticError.redefinition(vd.idTok));
                        }
                        // if previously a function declared? redeclaration
                        if (global.lookup(name) != null) {
                            grader.reportSemanticError(Project3SemanticError.redeclaration(g.varDec().Identifier()));
                        }
                        global.define(name, vd.type);
                        globalVars.putIfAbsent(name, vd.type);
                    } else {
                        // specifier SEMI : probably struct declaration or standalone specifier
                        SpecifierContext sc = g.specifier();
                        if (sc.STRUCT() != null && sc.LBRACE() != null) {
                            // full struct definition with no variable
                            // register tag
                            TerminalNode id = sc.Identifier();
                            String tag = id.getText();
                            Types.StructType existing = global.lookupTag(tag);
                            if (existing != null && existing.isComplete() && global.hasTagHere(tag)) {
                                grader.reportSemanticError(Project3SemanticError.redeclaration(id));
                            }
                            Types.StructType st = existing != null ? existing : new Types.StructType(tag);
                            global.defineTag(tag, st);
                            // fill members
                            List<SpecifierContext> specs = sc.specifier();
                            List<VarDecContext> vds = sc.varDec();
                            for (int i = 0; i < specs.size(); i++) {
                                framework.lang.Type base = typeFromSpecifier(specs.get(i));
                                VarDecl mv = resolveVarDec(vds.get(i), base);
                                if (mv.type instanceof Types.StructType) {
                                    Types.StructType memberStruct = (Types.StructType) mv.type;
                                    if (!memberStruct.isComplete()) {
                                        grader.reportSemanticError(Project3SemanticError.memberIncomplete(mv.idTok));
                                    }
                                }
                                st.addMember(mv.name, mv.type);
                            }
                            st.setComplete(true);
                        } else if (sc.STRUCT() != null && sc.LBRACE() == null) {
                            // e.g., struct Tag; declare incomplete tag
                            TerminalNode id = sc.Identifier();
                            String tag = id.getText();
                            if (global.lookupTag(tag) == null) {
                                Types.StructType st = new Types.StructType(tag);
                                global.defineTag(tag, st);
                            }
                        }
                    }
                }

                // after processing all, if no semantic error occurred, print results
                grader.print("Variables:\n");
                for (Map.Entry<String, framework.lang.Type> e : globalVars.entrySet()) {
                    grader.print(e.getKey() + ": " + e.getValue().fullPrint() + "\n");
                }
                grader.print("\n");
                grader.print("Functions:\n");
                for (Map.Entry<String, framework.lang.Type> e : globalFuncs.entrySet()) {
                    grader.print(e.getKey() + ": " + e.getValue().fullPrint() + "\n");
                }

                return null;
            }

            @Override
            public Void visitVarDecStmt(VarDecStmtContext ctx) {
                // local variable declaration: add to current scope and check duplicates
                framework.lang.Type base = typeFromSpecifier(ctx.specifier());
                VarDecl vd = resolveVarDec(ctx.varDec(), base);
                if (cur.containsHere(vd.name)) {
                    grader.reportSemanticError(Project3SemanticError.redefinition(vd.idTok));
                }
                // check incomplete type in local definition: if struct type and incomplete -> error
                if (vd.type instanceof Types.StructType) {
                    Types.StructType st = (Types.StructType) vd.type;
                    if (!st.isComplete()) {
                        grader.reportSemanticError(Project3SemanticError.definitionIncomplete(vd.idTok));
                    }
                }
                cur.define(vd.name, vd.type);
                // optional initializer
                if (ctx.ASSIGN() != null) {
                    checkExpr(ctx.expression());
                }
                return null;
            }

            @Override
            public Void visitBlockStmt(BlockStmtContext ctx) {
                // enter new scope
                Scope old = cur;
                cur = new Scope(old);
                for (StatementContext s : ctx.statement()) visit(s);
                cur = old;
                return null;
            }

            @Override
            public Void visitExpressionStmt(ExpressionStmtContext ctx) {
                checkExpr(ctx.expression());
                return null;
            }

            @Override
            public Void visitIfStmt(IfStmtContext ifc) {
                checkExpr(ifc.expression());
                visit(ifc.statement(0));
                if (ifc.statement().size() > 1) visit(ifc.statement(1));
                return null;
            }

            @Override
            public Void visitWhileStmt(WhileStmtContext w) {
                checkExpr(w.expression());
                visit(w.statement());
                return null;
            }

            @Override
            public Void visitReturnStmt(ReturnStmtContext r) {
                checkExpr(r.expression());
                return null;
            }

        }.visit(program);
    }
}
