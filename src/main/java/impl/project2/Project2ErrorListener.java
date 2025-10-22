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
            // 期望的第一个符号类型
            IntervalSet expected = parser.getExpectedTokens();
            int type = expected.getMinElement();
            String name = parser.getVocabulary().getSymbolicName(type);
            if (name != null) symbolName = name;
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
