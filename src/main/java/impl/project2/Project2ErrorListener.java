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
