# IDE Plugins & Pre-commit Hooks - Implementation Plan

**Status:** Future Enhancement
**Created:** 2026-01-01

## Overview

Extend JBCT tooling beyond CLI and Maven plugin to provide seamless IDE integration and git hook support.

### Goals

1. JBCT-compatible formatting in VS Code and IntelliJ IDEA
2. Real-time linting with all 37 rules
3. Project initialization wizard
4. Configuration update mechanism

### Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| GraalVM native-image | No | Build complexity not worth pre-commit perf gain |
| Repository structure | Monorepo | Single version, easier release sync |
| Config updates | GitHub-based | Check coding-technology repo for schema updates |
| Format on save | Opt-in | User preference, avoid surprising behavior |

---

## Module Structure

```
jbct-cli/
├── jbct-core/              # Existing - shared logic
├── jbct-cli/               # Existing - CLI
├── jbct-maven-plugin/      # Existing - Maven plugin
├── jbct-vscode/            # NEW - VS Code extension
├── jbct-intellij/          # NEW - IntelliJ plugin
├── jbct-hooks/             # NEW - Pre-commit hooks
└── slice-processor/        # Existing
```

---

## Phase 1: Core Enhancements

### 1.1 JSON Output for Linting

Add `--format=json` option to lint command for IDE parsing.

**CLI:**
```bash
jbct lint --format=json src/
```

**Output:**
```json
{
  "version": "0.5.0",
  "results": [
    {
      "file": "src/main/java/Foo.java",
      "line": 10,
      "column": 5,
      "endLine": 10,
      "endColumn": 25,
      "rule": "JBCT-RET-01",
      "severity": "error",
      "message": "Return statement should use Result.success()"
    }
  ],
  "summary": {
    "files": 15,
    "errors": 3,
    "warnings": 7
  }
}
```

**Implementation:**
- Add `OutputFormat` enum to `jbct-core`
- Modify `CstLinter` to return structured results
- Add JSON serialization in CLI

### 1.2 Config Updater Module

Extract and generalize update logic for plugin use.

**Location:** `jbct-core/src/main/java/org/pragmatica/jbct/update/`

```java
public interface ConfigUpdater {
    Result<UpdateInfo> checkForUpdates(Path projectRoot);
    Result<Void> applyUpdate(Path projectRoot, UpdateInfo info);
}

public record UpdateInfo(
    String currentVersion,
    String latestVersion,
    List<String> changedFiles,
    String releaseUrl
) {}
```

**Update check logic:**
1. Read current `jbct.toml` version (add `version` field)
2. Fetch `https://api.github.com/repos/siy/coding-technology/releases/latest`
3. Compare versions
4. Return update info if newer available

---

## Phase 2: VS Code Extension

### 2.1 Project Structure

```
jbct-vscode/
├── package.json
├── tsconfig.json
├── .vscodeignore
├── src/
│   ├── extension.ts           # Activation, registration
│   ├── formatter.ts           # DocumentFormattingEditProvider
│   ├── linter.ts              # DiagnosticCollection management
│   ├── commands/
│   │   ├── format.ts
│   │   ├── lint.ts
│   │   ├── check.ts
│   │   └── init.ts
│   ├── webview/
│   │   └── initWizard.ts      # Project init UI
│   └── util/
│       ├── jbct.ts            # JAR invocation
│       └── config.ts          # Settings management
├── jars/
│   └── jbct.jar               # Bundled (copy from jbct-cli/target)
├── webview/
│   └── init.html              # Init wizard HTML
└── test/
    └── extension.test.ts
```

### 2.2 package.json

```json
{
  "name": "jbct",
  "displayName": "JBCT - Java Backend Coding Technology",
  "description": "Format and lint Java code with JBCT",
  "version": "0.5.0",
  "publisher": "pragmatica",
  "engines": {
    "vscode": "^1.85.0"
  },
  "categories": ["Formatters", "Linters"],
  "activationEvents": [
    "onLanguage:java",
    "workspaceContains:jbct.toml"
  ],
  "main": "./out/extension.js",
  "contributes": {
    "commands": [
      {"command": "jbct.format", "title": "JBCT: Format Document"},
      {"command": "jbct.lint", "title": "JBCT: Lint Document"},
      {"command": "jbct.check", "title": "JBCT: Check (Format + Lint)"},
      {"command": "jbct.init", "title": "JBCT: Initialize Project"},
      {"command": "jbct.update", "title": "JBCT: Update Configuration"}
    ],
    "configuration": {
      "title": "JBCT",
      "properties": {
        "jbct.formatOnSave": {
          "type": "boolean",
          "default": false,
          "description": "Format Java files on save"
        },
        "jbct.lintOnSave": {
          "type": "boolean",
          "default": true,
          "description": "Lint Java files on save"
        },
        "jbct.javaPath": {
          "type": "string",
          "default": "java",
          "description": "Path to Java executable"
        }
      }
    }
  }
}
```

### 2.3 Core Implementation

**extension.ts:**
```typescript
import * as vscode from 'vscode';
import { JbctFormatter } from './formatter';
import { JbctLinter } from './linter';
import { registerCommands } from './commands';

export function activate(context: vscode.ExtensionContext) {
  const jarPath = context.asAbsolutePath('jars/jbct.jar');

  // Formatter
  const formatter = new JbctFormatter(jarPath);
  context.subscriptions.push(
    vscode.languages.registerDocumentFormattingEditProvider(
      'java', formatter
    )
  );

  // Linter
  const linter = new JbctLinter(jarPath);
  const diagnostics = vscode.languages.createDiagnosticCollection('jbct');
  context.subscriptions.push(diagnostics);

  // Lint on save (if enabled)
  vscode.workspace.onDidSaveTextDocument(doc => {
    if (doc.languageId === 'java') {
      const config = vscode.workspace.getConfiguration('jbct');
      if (config.get('lintOnSave')) {
        linter.lint(doc, diagnostics);
      }
    }
  });

  // Register commands
  registerCommands(context, jarPath, linter, diagnostics);
}
```

**formatter.ts:**
```typescript
import * as vscode from 'vscode';
import { runJbct } from './util/jbct';

export class JbctFormatter implements vscode.DocumentFormattingEditProvider {
  constructor(private jarPath: string) {}

  async provideDocumentFormattingEdits(
    document: vscode.TextDocument
  ): Promise<vscode.TextEdit[]> {
    const content = document.getText();
    const result = await runJbct(this.jarPath, ['format', '--stdin'], content);

    if (result.exitCode !== 0) {
      vscode.window.showErrorMessage(`JBCT format failed: ${result.stderr}`);
      return [];
    }

    const fullRange = new vscode.Range(
      document.positionAt(0),
      document.positionAt(content.length)
    );

    return [vscode.TextEdit.replace(fullRange, result.stdout)];
  }
}
```

**linter.ts:**
```typescript
import * as vscode from 'vscode';
import { runJbct } from './util/jbct';

interface LintResult {
  file: string;
  line: number;
  column: number;
  endLine: number;
  endColumn: number;
  rule: string;
  severity: 'error' | 'warning';
  message: string;
}

export class JbctLinter {
  constructor(private jarPath: string) {}

  async lint(
    document: vscode.TextDocument,
    collection: vscode.DiagnosticCollection
  ): Promise<void> {
    const result = await runJbct(
      this.jarPath,
      ['lint', '--format=json', document.uri.fsPath]
    );

    if (result.exitCode > 1) {
      console.error('JBCT lint error:', result.stderr);
      return;
    }

    try {
      const output = JSON.parse(result.stdout);
      const diagnostics = output.results.map((r: LintResult) => {
        const range = new vscode.Range(
          r.line - 1, r.column - 1,
          r.endLine - 1, r.endColumn - 1
        );
        const severity = r.severity === 'error'
          ? vscode.DiagnosticSeverity.Error
          : vscode.DiagnosticSeverity.Warning;

        const diagnostic = new vscode.Diagnostic(
          range,
          `${r.rule}: ${r.message}`,
          severity
        );
        diagnostic.source = 'jbct';
        diagnostic.code = r.rule;
        return diagnostic;
      });

      collection.set(document.uri, diagnostics);
    } catch (e) {
      console.error('Failed to parse lint output:', e);
    }
  }
}
```

### 2.4 Init Wizard

WebView-based wizard for `jbct init`:

1. Project type: Standard / Slice
2. Package name
3. Format settings (line length, indent)
4. Lint rule configuration

Calls `jbct init` with appropriate flags.

### 2.5 Build & Distribution

```bash
# Build
cd jbct-vscode
npm install
npm run compile

# Copy JAR from CLI build
cp ../jbct-cli/target/jbct.jar jars/

# Package
npx vsce package

# Publish
npx vsce publish
```

**CI/CD:**
- Build on tag push
- Copy JAR from jbct-cli build
- Publish to VS Code Marketplace

---

## Phase 3: IntelliJ IDEA Plugin

### 3.1 Project Structure

```
jbct-intellij/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── src/main/
│   ├── java/org/pragmatica/jbct/idea/
│   │   ├── JbctBundle.java              # Message bundle
│   │   ├── format/
│   │   │   ├── JbctFormattingService.java
│   │   │   └── JbctCodeStyleSettings.java
│   │   ├── lint/
│   │   │   ├── JbctExternalAnnotator.java
│   │   │   └── JbctInspection.java
│   │   ├── wizard/
│   │   │   ├── JbctModuleBuilder.java
│   │   │   └── JbctProjectWizardStep.java
│   │   ├── settings/
│   │   │   └── JbctSettingsConfigurable.java
│   │   ├── update/
│   │   │   └── JbctUpdateChecker.java
│   │   └── util/
│   │       └── JbctRunner.java
│   └── resources/
│       ├── META-INF/plugin.xml
│       ├── messages/JbctBundle.properties
│       └── fileTemplates/
│           ├── JBCT_ValueObject.java.ft
│           └── JBCT_UseCase.java.ft
├── libs/
│   └── jbct-core.jar                    # Bundled
└── src/test/
```

### 3.2 build.gradle.kts

```kotlin
plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.0"
}

group = "org.pragmatica"
version = "0.5.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2024.2")
    type.set("IC")
    plugins.set(listOf("java"))
}

dependencies {
    implementation(files("libs/jbct-core.jar"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("252.*")
        changeNotes.set("""
            Initial release with formatting and linting support.
        """.trimIndent())
    }

    prepareSandbox {
        doLast {
            copy {
                from("libs/jbct-core.jar")
                into(destinationDir.resolve("${pluginName.get()}/lib"))
            }
        }
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
```

### 3.3 plugin.xml

```xml
<idea-plugin>
    <id>org.pragmatica.jbct</id>
    <name>JBCT - Java Backend Coding Technology</name>
    <version>0.5.0</version>
    <vendor email="info@pragmatica.org" url="https://pragmatica.org">
        Pragmatica
    </vendor>

    <description><![CDATA[
        Format and lint Java code with JBCT (Java Backend Coding Technology).
        <ul>
            <li>Chain-aligned formatting</li>
            <li>37 lint rules for functional Java</li>
            <li>Project initialization wizard</li>
        </ul>
    ]]></description>

    <idea-version since-build="242" until-build="252.*"/>
    <depends>com.intellij.modules.java</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Formatting -->
        <formattingService
            implementation="org.pragmatica.jbct.idea.format.JbctFormattingService"/>

        <!-- Linting -->
        <externalAnnotator
            language="JAVA"
            implementationClass="org.pragmatica.jbct.idea.lint.JbctExternalAnnotator"/>
        <globalInspection
            implementationClass="org.pragmatica.jbct.idea.lint.JbctInspection"
            displayName="JBCT Code Style"
            groupName="JBCT"
            enabledByDefault="true"
            level="WARNING"/>

        <!-- Settings -->
        <projectConfigurable
            parentId="tools"
            id="org.pragmatica.jbct.settings"
            displayName="JBCT"
            instance="org.pragmatica.jbct.idea.settings.JbctSettingsConfigurable"/>
        <projectService
            serviceImplementation="org.pragmatica.jbct.idea.settings.JbctSettings"/>

        <!-- Project Wizard -->
        <directoryProjectGenerator
            implementation="org.pragmatica.jbct.idea.wizard.JbctProjectGenerator"/>
        <moduleBuilder
            builderClass="org.pragmatica.jbct.idea.wizard.JbctModuleBuilder"
            id="JBCT_MODULE"
            order="first"/>

        <!-- Startup -->
        <postStartupActivity
            implementation="org.pragmatica.jbct.idea.update.JbctUpdateChecker"/>

        <!-- File Templates -->
        <fileTemplateGroup
            implementation="org.pragmatica.jbct.idea.template.JbctFileTemplateProvider"/>
    </extensions>

    <actions>
        <action id="Jbct.Format"
                class="org.pragmatica.jbct.idea.action.FormatAction"
                text="Format with JBCT"
                description="Format current file with JBCT">
            <add-to-group group-id="CodeFormatGroup" anchor="first"/>
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt shift J"/>
        </action>

        <action id="Jbct.Lint"
                class="org.pragmatica.jbct.idea.action.LintAction"
                text="Lint with JBCT"
                description="Run JBCT linter on current file">
            <add-to-group group-id="AnalyzeMenu" anchor="after" relative-to-action="InspectCode"/>
        </action>
    </actions>
</idea-plugin>
```

### 3.4 Core Implementation

**JbctFormattingService.java:**
```java
public class JbctFormattingService implements AsyncDocumentFormattingService {

    @Override
    public @NotNull Set<Feature> getFeatures() {
        return Set.of(Feature.FORMAT_DOCUMENT);
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return file.getLanguage() == JavaLanguage.INSTANCE;
    }

    @Override
    public @NotNull FormattingTask createFormattingTask(
            @NotNull AsyncFormattingRequest request) {
        return new JbctFormattingTask(request);
    }
}

class JbctFormattingTask implements FormattingTask {
    private final AsyncFormattingRequest request;

    @Override
    public void run() {
        String source = request.getDocumentText();
        Result<String> result = JbctFormatter.format(source);

        result.fold(
            cause -> request.onError("JBCT", cause.message()),
            formatted -> {
                if (!formatted.equals(source)) {
                    request.onTextReady(formatted);
                }
            }
        );
    }
}
```

**JbctExternalAnnotator.java:**
```java
public class JbctExternalAnnotator
        extends ExternalAnnotator<PsiFile, List<LintResult>> {

    @Override
    public @Nullable PsiFile collectInformation(@NotNull PsiFile file) {
        if (!(file instanceof PsiJavaFile)) return null;
        return file;
    }

    @Override
    public @Nullable List<LintResult> doAnnotate(PsiFile file) {
        String source = file.getText();
        return JbctLinter.lint(source, file.getName())
                         .fold(cause -> List.of(), results -> results);
    }

    @Override
    public void apply(
            @NotNull PsiFile file,
            List<LintResult> results,
            @NotNull AnnotationHolder holder) {
        for (LintResult result : results) {
            TextRange range = findRange(file, result);
            HighlightSeverity severity = result.severity().equals("error")
                ? HighlightSeverity.ERROR
                : HighlightSeverity.WARNING;

            holder.newAnnotation(severity, result.rule() + ": " + result.message())
                  .range(range)
                  .create();
        }
    }
}
```

### 3.5 Build & Distribution

```bash
# Build
cd jbct-intellij
./gradlew build

# Copy JAR from core build
cp ../jbct-core/target/jbct-core-*.jar libs/jbct-core.jar

# Build plugin zip
./gradlew buildPlugin

# Publish
./gradlew publishPlugin
```

---

## Phase 4: Pre-commit Hooks

### 4.1 Project Structure

```
jbct-hooks/
├── .pre-commit-hooks.yaml
├── bin/
│   ├── jbct                    # Unix wrapper
│   └── jbct.bat                # Windows wrapper
├── lib/
│   └── jbct.jar                # Bundled
└── README.md
```

### 4.2 .pre-commit-hooks.yaml

```yaml
- id: jbct-format
  name: JBCT Format
  description: Format Java files with JBCT
  entry: bin/jbct format
  language: script
  files: \.java$
  types: [java]

- id: jbct-lint
  name: JBCT Lint
  description: Lint Java files with JBCT
  entry: bin/jbct lint
  language: script
  files: \.java$
  types: [java]

- id: jbct-check
  name: JBCT Check
  description: Format check + lint (for CI)
  entry: bin/jbct check
  language: script
  files: \.java$
  types: [java]
```

### 4.3 Wrapper Scripts

**bin/jbct (Unix):**
```bash
#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_PATH="${SCRIPT_DIR}/lib/jbct.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "Error: jbct.jar not found at $JAR_PATH" >&2
    exit 1
fi

exec java \
    -XX:+TieredCompilation \
    -XX:TieredStopAtLevel=1 \
    -jar "$JAR_PATH" \
    "$@"
```

**bin/jbct.bat (Windows):**
```batch
@echo off
setlocal

set SCRIPT_DIR=%~dp0..
set JAR_PATH=%SCRIPT_DIR%\lib\jbct.jar

if not exist "%JAR_PATH%" (
    echo Error: jbct.jar not found at %JAR_PATH% >&2
    exit /b 1
)

java -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -jar "%JAR_PATH%" %*
```

### 4.4 Usage

**User's `.pre-commit-config.yaml`:**
```yaml
repos:
  - repo: https://github.com/siy/jbct-cli
    rev: v0.5.0
    hooks:
      - id: jbct-format
      - id: jbct-lint
```

**Installation:**
```bash
pip install pre-commit
pre-commit install
```

### 4.5 CI Integration

**GitHub Actions:**
```yaml
name: Lint
on: [pull_request]

jobs:
  pre-commit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
      - uses: pre-commit/action@v3.0.0
```

---

## Phase 5: Release Process

### 5.1 Version Synchronization

All modules share version from parent POM:
```xml
<properties>
    <revision>0.5.0</revision>
</properties>
```

VS Code and IntelliJ plugins read version from build.

### 5.2 Release Workflow

```yaml
# .github/workflows/release.yml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  build-core:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
      - run: mvn package -DskipTests
      - uses: actions/upload-artifact@v4
        with:
          name: jars
          path: |
            jbct-cli/target/jbct.jar
            jbct-core/target/jbct-core-*.jar

  publish-vscode:
    needs: build-core
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/download-artifact@v4
        with:
          name: jars
          path: jbct-vscode/jars/
      - run: npm ci
        working-directory: jbct-vscode
      - run: npx vsce publish
        working-directory: jbct-vscode
        env:
          VSCE_PAT: ${{ secrets.VSCE_PAT }}

  publish-intellij:
    needs: build-core
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/download-artifact@v4
        with:
          name: jars
          path: jbct-intellij/libs/
      - run: ./gradlew publishPlugin
        working-directory: jbct-intellij
        env:
          PUBLISH_TOKEN: ${{ secrets.JETBRAINS_TOKEN }}
          CERTIFICATE_CHAIN: ${{ secrets.CERTIFICATE_CHAIN }}
          PRIVATE_KEY: ${{ secrets.PRIVATE_KEY }}
          PRIVATE_KEY_PASSWORD: ${{ secrets.PRIVATE_KEY_PASSWORD }}

  publish-hooks:
    needs: build-core
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/download-artifact@v4
        with:
          name: jars
          path: jbct-hooks/lib/
      # Hooks are published as part of the main repo
      # Users reference the tag directly
```

---

## Implementation Order

### Priority 1: Core Enhancements
1. Add JSON output format to lint command
2. Add `--stdin` mode to format command
3. Extract ConfigUpdater to jbct-core

### Priority 2: VS Code Extension
1. Basic extension scaffold
2. Format command (spawn JAR)
3. Lint on save with diagnostics
4. Settings UI
5. Init wizard
6. Update checker
7. Marketplace publishing

### Priority 3: IntelliJ Plugin
1. Basic plugin scaffold
2. FormattingService integration
3. ExternalAnnotator for linting
4. Settings configurable
5. Module builder for project wizard
6. Update checker
7. Marketplace publishing

### Priority 4: Pre-commit Hooks
1. Hook repository structure
2. Wrapper scripts
3. Documentation
4. CI examples

---

## Success Metrics

- VS Code Marketplace installs
- JetBrains Marketplace downloads
- Pre-commit hook adoption (GitHub dependency graph)
- Issue/bug rate per release
- Format-on-save adoption rate (opt-in tracking if consented)
