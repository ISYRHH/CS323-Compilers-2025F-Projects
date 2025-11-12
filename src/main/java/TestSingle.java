import framework.project3.Grader;
import java.io.FileInputStream;
import java.io.InputStream;

public class TestSingle {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: TestSingle <input.splc>");
            System.exit(2);
        }
        String path = args[0];
        try (InputStream in = new FileInputStream(path)) {
            Grader g = new Grader(in, System.out);
            g.run();
        }
    }
}
