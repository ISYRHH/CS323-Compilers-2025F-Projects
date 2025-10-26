import framework.project2.Grader;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class GenerateExpected {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: GenerateExpected <path/to/file.splc>");
            System.exit(2);
        }
        String path = args[0];
        boolean expectError = path.toLowerCase().contains("error");
        try (InputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(bout, true, StandardCharsets.UTF_8);
            Grader grader = new Grader(in, ps, expectError);
            try {
                grader.run();
            } catch (Exception e) {
                e.printStackTrace(ps);
            }
            String out = bout.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            System.out.print(out);
        }
    }
}
