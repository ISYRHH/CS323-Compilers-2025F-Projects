import generated.Splc.SplcLexer;
import org.antlr.v4.runtime.*;

import java.io.FileInputStream;

public class TokenDumper {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: TokenDumper <file>");
            System.exit(2);
        }
        CharStream input = CharStreams.fromStream(new FileInputStream(args[0]));
        SplcLexer lexer = new SplcLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            String name = lexer.getVocabulary().getSymbolicName(t.getType());
            System.out.printf("%3d: %-12s %-10s (line %d)\n", i, name, t.getText().replace("\n","\\n"), t.getLine());
        }
    }
}
