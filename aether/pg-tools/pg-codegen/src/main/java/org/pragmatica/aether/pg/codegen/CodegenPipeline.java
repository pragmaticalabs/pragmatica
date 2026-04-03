package org.pragmatica.aether.pg.codegen;

import org.pragmatica.aether.pg.schema.builder.MigrationProcessor;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.lang.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;


/// End-to-end pipeline: SQL migrations → parse → schema → Java source files.
public final class CodegenPipeline {
    private final CodegenConfig config;

    public CodegenPipeline(CodegenConfig config) {
        this.config = config;
    }

    public Result<List<GeneratedFile>> generate(List<String> migrationScripts) {
        return MigrationProcessor.create().processAll(migrationScripts)
                                        .flatMap(this::generateFromSchema);
    }

    public Result<List<GeneratedFile>> generateFromSchema(Schema schema) {
        var files = new ArrayList<GeneratedFile>();
        var recordGen = new RecordGenerator(config);
        var enumGen = new EnumGenerator(config);
        for (var table : schema.tables().values()) {
            var result = recordGen.generate(table);
            if (result.isFailure()) return result.map(f -> List.of(f));
            files.add(result.unwrap());
        }
        for (var enumType : schema.enumTypes().values()) {
            var result = enumGen.generate(enumType);
            if (result.isFailure()) return result.map(f -> List.of(f));
            files.add(result.unwrap());
        }
        return Result.success(files);
    }

    public Result<List<GeneratedFile>> generateAndWrite(List<String> migrationScripts) {
        return generate(migrationScripts).flatMap(this::writeFiles);
    }

    public Result<List<GeneratedFile>> writeFiles(List<GeneratedFile> files) {
        for (var file : files) {try {
            Files.createDirectories(file.path().getParent());
            Files.writeString(file.path(), file.content());
        } catch (IOException e) {
            return new CodegenError.IoError("Failed to write " + file.path() + ": " + e.getMessage()).result();
        }}
        return Result.success(files);
    }
}
