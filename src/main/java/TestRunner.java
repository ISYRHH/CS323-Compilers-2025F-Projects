import framework.project2.Grader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class TestRunner {
    public static void main(String[] args) throws IOException {
        Path testdir = Paths.get("testcases/project2");
        if (!Files.isDirectory(testdir)) {
            System.err.println("testcases/project2 directory not found");
            System.exit(2);
        }

        List<Path> splcFiles = new ArrayList<>();
        try (var s = Files.list(testdir)) {
            s.filter(p -> p.toString().endsWith(".splc")).forEach(splcFiles::add);
        }

        int total = 0, passed = 0;
        List<String> failed = new ArrayList<>();

        for (Path splc : splcFiles) {
            total++;
            String base = splc.getFileName().toString();
            String expectedName = base.replaceAll("\\.splc$", ".txt");
            Path expectedPath = testdir.resolve(expectedName);

            boolean expectError = base.toLowerCase().contains("error");

            // Capture Grader output
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(bout, true, StandardCharsets.UTF_8);

            try (InputStream in = new FileInputStream(splc.toFile())) {
                Grader grader = new Grader(in, ps, expectError);
                try {
                    grader.run();
                } catch (Exception e) {
                    // If grader throws, capture stack in output
                    e.printStackTrace(ps);
                }
            }

            String actual = bout.toString(StandardCharsets.UTF_8);
            // Normalize line endings to \n for comparison
            actual = actual.replace("\r\n", "\n").replace('\r', '\n');

            String expected = "";
            if (Files.exists(expectedPath)) {
                expected = Files.readString(expectedPath, StandardCharsets.UTF_8);
                expected = expected.replace("\r\n", "\n").replace('\r', '\n');
            } else {
                // If no expected file, treat expected as empty
                expected = "";
            }

            // 比较时对首尾空白宽容一些，避免行尾换行差异导致误报
            boolean match = actual.trim().equals(expected.trim());
            if (match) {
                passed++;
                System.out.printf("[PASS] %s\n", base);
            } else {
                failed.add(base);
                System.out.printf("[FAIL] %s\n--- Expected ---\n%s\n--- Actual ---\n%s\n", base, expected, actual);
            }
        }

        System.out.println();
        System.out.printf("Total: %d, Passed: %d, Failed: %d\n", total, passed, total - passed);

        if (total - passed > 0) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }
}
