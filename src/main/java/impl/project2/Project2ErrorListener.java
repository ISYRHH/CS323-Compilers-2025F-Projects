package impl.project2;

import framework.project2.Grader;
import framework.project2.MissingSymbolError;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.ParseCancellationException;

public class Project2ErrorListener extends BaseErrorListener {
    private final Grader grader;
    public Project2ErrorListener(Grader grader) {
        this.grader = grader;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer,
                            Object offendingSymbol,
                            int line,
                            int charPositionInLine,
                            String msg,
                            RecognitionException e) {
        // 提取缺失符号名称与行号（0-based）
        String symbolName = "UNKNOWN";
        int errorLine = line - 1;
        if (recognizer instanceof Parser) {
            Parser parser = (Parser) recognizer;
            // 期望的符号集合
            IntervalSet expected = parser.getExpectedTokens();
            int[] elems = expected.toArray();
            if (elems.length > 0) {
                // 优先选出右大括号等对结构影响较大的符号（当缺失 '}' 时更直观）
                for (int t : elems) {
                    String n = parser.getVocabulary().getSymbolicName(t);
                    if ("RBRACE".equals(n) || "SEMI".equals(n) || "RPAREN".equals(n)) {
                        symbolName = n;
                        break;
                    }
                }
                // 如果上面没有选出优先符号，则选择第一个有名称且不是 EOF 的符号
                if ("UNKNOWN".equals(symbolName)) {
                    for (int t : elems) {
                        String n = parser.getVocabulary().getSymbolicName(t);
                        if (n != null && !"EOF".equals(n)) {
                            symbolName = n;
                            break;
                        }
                    }
                }
                // 最后兜底：使用 vocabulary 的 display name
                if ("UNKNOWN".equals(symbolName)) {
                    int t = elems[0];
                    symbolName = parser.getVocabulary().getDisplayName(t);
                }
            }
            // 启发式修复：如果触发错误的 token 看起来像是类型或标识符，且前一个 token 是标识符，
            // 并且当前解析上下文在 funcArgs 规则内，很可能是缺少逗号（参数间缺少逗号，如 "int x int y"）
            if (offendingSymbol instanceof Token) {
                Token tok = (Token) offendingSymbol;
                org.antlr.v4.runtime.TokenStream stream = (org.antlr.v4.runtime.TokenStream) parser.getInputStream();
                // 取前一个 token（如果存在）
                int prevIdx = tok.getTokenIndex() - 1;
                Token prev = null;
                if (prevIdx >= 0) {
                    prev = stream.get(prevIdx);
                }

                // 仅保留最小回溯启发式：基于 token 流回溯查找 LPAREN 判断是否处于函数参数列表，
                // 若在参数列表中且前一个 token 为 Identifier 且后续 token/触发 token 为类型或 Identifier，则报告 COMMA
                String prevName = prev == null ? null : parser.getVocabulary().getSymbolicName(prev.getType());
                String offName = parser.getVocabulary().getSymbolicName(tok.getType());

                boolean inFuncArgs = false;
                // 回溯有限步长查找最近的 LPAREN，避免误判或过度扫描
                try {
                    if (prev != null) {
                        int backIdx = prev.getTokenIndex();
                        int steps = 0;
                        while (backIdx >= 0 && steps < 40) {
                            Token t = stream.get(backIdx);
                            String tn = parser.getVocabulary().getSymbolicName(t.getType());
                            if ("LPAREN".equals(tn)) {
                                inFuncArgs = true;
                                break;
                            }
                            if ("RBRACE".equals(tn) || "SEMI".equals(tn) || "RPAREN".equals(tn)) {
                                break;
                            }
                            backIdx--;
                            steps++;
                        }
                    }
                } catch (Exception ignored) {
                }

                if (inFuncArgs && prevName != null && "Identifier".equals(prevName)) {
                    // 看紧跟 prev 的 token（可能是 offendingSymbol 本身或其后的 token）
                    Token after = null;
                    try {
                        int afterIdx = prev.getTokenIndex() + 1;
                        if (afterIdx >= 0) after = stream.get(afterIdx);
                    } catch (Exception ignored) {
                    }
                    String afterName = after == null ? null : parser.getVocabulary().getSymbolicName(after.getType());

                    if ("INT".equals(afterName) || "CHAR".equals(afterName) || "STRUCT".equals(afterName) || "Identifier".equals(afterName) || "INT".equals(offName) || "CHAR".equals(offName) || "STRUCT".equals(offName) || "Identifier".equals(offName)) {
                        symbolName = "COMMA";
                        if (prev != null) {
                            errorLine = prev.getLine() - 1;
                        }
                    }
                }
            }
            // 取前一个有效 token 的行号
            if (offendingSymbol instanceof Token) {
                Token tok = (Token) offendingSymbol;
                int idx = tok.getTokenIndex() - 1;
                if (idx >= 0) {
                    Token prev = ((org.antlr.v4.runtime.TokenStream)parser.getInputStream()).get(idx);
                    errorLine = prev.getLine() - 1;
                }
            }
        }
        MissingSymbolError missingSymbol = new MissingSymbolError(symbolName, errorLine);
        this.grader.getWriter().println(missingSymbol);
        throw new ParseCancellationException();
    }
}
