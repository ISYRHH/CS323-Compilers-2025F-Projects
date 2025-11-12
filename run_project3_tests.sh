#!/usr/bin/env zsh
# Run Project3 testcases: compile sources, run each .splc in its own JVM and compare output to .txt
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
SRC_DIR="$ROOT_DIR/src/main/java"
OUT_DIR="$ROOT_DIR/target/production/CS323-Compilers-2025F-Projects"
ANTLR_JAR="$ROOT_DIR/libs/antlr-4.13.2-complete.jar"
TEST_DIR="$ROOT_DIR/testcases/project3"

mkdir -p "$OUT_DIR"

# Compile all Java sources (including our TestSingle)
echo "Compiling Java sources..."
javac -cp "$ANTLR_JAR:$OUT_DIR" -d "$OUT_DIR" $(find "$SRC_DIR" -name '*.java')

# Run tests
fail_count=0
total=0
for spl in "$TEST_DIR"/*.splc; do
  total=$((total+1))
  base=$(basename "$spl" .splc)
  expected="$TEST_DIR/$base.txt"
  out="$TEST_DIR/$base.actual.txt"
  echo "Running $base..."
  # run each test in its own JVM because grader may call System.exit on semantic error
  if java -cp "$ANTLR_JAR:$OUT_DIR" TestSingle "$spl" > "$out" 2>&1; then
    :
  fi
  if [ -f "$expected" ]; then
    if diff -u "$expected" "$out" > /dev/null; then
      echo "  PASS: $base"
    else
      echo "  FAIL: $base (output differs)"
      echo "  --- Expected: $expected"
      echo "  --- Actual:   $out"
      fail_count=$((fail_count+1))
    fi
  else
    echo "  WARN: no expected output file $expected; wrote actual to $out"
  fi
done

echo "\nSummary: $total tests, $fail_count failures."
if [ $fail_count -ne 0 ]; then
  exit 1
fi
