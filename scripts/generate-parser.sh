#!/bin/bash
# Generate Java 25 CST parser from PEG grammar using java-peglib
#
# This script:
# 1. Builds java-peglib if not already installed
# 2. Runs the generator to produce Java25Parser.java
# 3. Places it in jbct-core/src/main/java/org/pragmatica/jbct/parser/

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PEGLIB_DIR="${PEGLIB_DIR:-$PROJECT_DIR/../java-peglib}"

GRAMMAR_FILE="$PROJECT_DIR/jbct-core/src/main/resources/grammars/java25.peg"
OUTPUT_DIR="$PROJECT_DIR/jbct-core/src/main/java/org/pragmatica/jbct/parser"
OUTPUT_FILE="$OUTPUT_DIR/Java25Parser.java"

PACKAGE_NAME="org.pragmatica.jbct.parser"
CLASS_NAME="Java25Parser"

echo "=== JBCT Parser Generator ==="
echo "Grammar: $GRAMMAR_FILE"
echo "Output:  $OUTPUT_FILE"

# Check grammar exists
if [ ! -f "$GRAMMAR_FILE" ]; then
    echo "Error: Grammar file not found: $GRAMMAR_FILE"
    exit 1
fi

# Check java-peglib exists
if [ ! -d "$PEGLIB_DIR" ]; then
    echo "Error: java-peglib not found at: $PEGLIB_DIR"
    echo "Set PEGLIB_DIR environment variable to java-peglib location"
    exit 1
fi

# Build java-peglib if needed
echo ""
echo "Building java-peglib..."
cd "$PEGLIB_DIR"
mvn install -DskipTests -Djbct.skip=true -q

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Generate parser using java-peglib
echo ""
echo "Generating CST parser..."
cd "$PROJECT_DIR"

# Create a temporary Java file to run the generator
TEMP_DIR=$(mktemp -d)
GENERATOR_FILE="$TEMP_DIR/GenerateParser.java"

cat > "$GENERATOR_FILE" << 'GENERATOR_CODE'
import org.pragmatica.peg.PegParser;
import java.nio.file.Files;
import java.nio.file.Path;

public class GenerateParser {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: GenerateParser <grammar-file> <package> <class-name> <output-file>");
            System.exit(1);
        }

        var grammarFile = args[0];
        var packageName = args[1];
        var className = args[2];
        var outputFile = args[3];

        var grammar = Files.readString(Path.of(grammarFile));

        var result = PegParser.generateCstParser(grammar, packageName, className);

        if (result.isFailure()) {
            System.err.println("Generation failed: " + result);
            System.exit(1);
        }

        var source = result.unwrap();
        Files.writeString(Path.of(outputFile), source);

        System.out.println("Generated: " + outputFile);
        System.out.println("Lines: " + source.split("\n").length);
    }
}
GENERATOR_CODE

# Compile and run generator
PEGLIB_JAR="$HOME/.m2/repository/org/pragmatica/peglib/0.1.0-SNAPSHOT/peglib-0.1.0-SNAPSHOT.jar"
CORE_JAR="$HOME/.m2/repository/org/pragmatica-lite/core/0.8.4/core-0.8.4.jar"
CLASSPATH="$PEGLIB_JAR:$CORE_JAR"

echo "Compiling generator..."
javac -cp "$CLASSPATH" -d "$TEMP_DIR" "$GENERATOR_FILE"

echo "Running generator..."
java -cp "$TEMP_DIR:$CLASSPATH" GenerateParser "$GRAMMAR_FILE" "$PACKAGE_NAME" "$CLASS_NAME" "$OUTPUT_FILE"

# Cleanup
rm -rf "$TEMP_DIR"

echo ""
echo "=== Done ==="
echo "Parser generated at: $OUTPUT_FILE"
