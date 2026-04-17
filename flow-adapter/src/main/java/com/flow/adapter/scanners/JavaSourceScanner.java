package com.flow.adapter.scanners;

import com.flow.adapter.Model.GraphModel;
import com.flow.adapter.util.PackageUtil;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaSourceScanner implements SourceCodeAnalyzer {

  private static final Logger logger = LoggerFactory.getLogger(JavaSourceScanner.class);

  @Override
  public void analyze(GraphModel model, Path srcRoot) throws IOException {
    analyze(model, srcRoot, Collections.emptyList());
  }

  public void analyze(GraphModel model, Path srcRoot, List<Path> classpathJars) throws IOException {
    JavaParser parser = buildParser(srcRoot, classpathJars);
    MethodCallAnalyzer analyzer = new MethodCallAnalyzer();
    scanJavaFiles(model, srcRoot, parser, analyzer);
  }

  private JavaParser buildParser(Path srcRoot, List<Path> classpathJars) {
    CombinedTypeSolver solver = createTypeSolver(srcRoot, classpathJars);
    ParserConfiguration config = new ParserConfiguration();
    config.setLanguageLevel(LanguageLevel.JAVA_17);
    config.setSymbolResolver(new JavaSymbolSolver(solver));
    return new JavaParser(config);
  }

  private CombinedTypeSolver createTypeSolver(Path srcRoot, List<Path> classpathJars) {
    // Give JavaParserTypeSolver a JAVA_17 config so it can parse records when resolving types.
    // Note: we intentionally do NOT set a SymbolResolver here to avoid circular initialization.
    ParserConfiguration solverConfig = new ParserConfiguration();
    solverConfig.setLanguageLevel(LanguageLevel.JAVA_17);

    CombinedTypeSolver solver = new CombinedTypeSolver();
    solver.add(new ReflectionTypeSolver());
    solver.add(new JavaParserTypeSolver(srcRoot, solverConfig));
    for (Path jar : classpathJars) {
      try {
        solver.add(new JarTypeSolver(jar));
      } catch (IOException e) {
        logger.warn("Could not add classpath JAR to type solver: {} -> {}", jar, e.getMessage());
      }
    }
    return solver;
  }

  private void scanJavaFiles(GraphModel model, Path srcRoot, JavaParser parser, MethodCallAnalyzer analyzer) throws IOException {
    try (Stream<Path> walk = Files.walk(srcRoot)) {
      walk.filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.toString().contains("/src/test/"))
          .filter(p -> !p.toString().contains("/test/"))
          .forEach(p -> parseFile(model, p, parser, analyzer));
    }
  }

  private void parseFile(GraphModel model, Path file, JavaParser parser, MethodCallAnalyzer analyzer) {
    try {
      ParseResult<CompilationUnit> result = parser.parse(file);
      if (!result.isSuccessful() || result.getResult().isEmpty()) {
        result.getProblems().forEach(problem ->
            logger.error("Parse fail: {} -> {}", file, problem.getMessage()));
        return;
      }
      processClasses(model, result.getResult().get(), analyzer);
    } catch (Exception e) {
      logger.error("Parse fail: {} -> {}", file, e.getMessage(), e);
    }
  }

  private void processClasses(GraphModel model, CompilationUnit cu, MethodCallAnalyzer analyzer) {
    cu.findAll(ClassOrInterfaceDeclaration.class)
        .forEach(cls -> processClass(model, cu, cls, analyzer));
    cu.findAll(RecordDeclaration.class)
        .forEach(rec -> processRecord(model, cu, rec, analyzer));
  }

  private void processClass(GraphModel model, CompilationUnit cu, ClassOrInterfaceDeclaration cls, MethodCallAnalyzer analyzer) {
    String pkg = extractPackage(cu);
    String fqn = buildFqn(pkg, cls.getName().asString());
    String module = PackageUtil.deriveModule(pkg);
    cls.getMethods().forEach(md -> analyzer.analyze(model, cu, fqn, pkg, module, md));
  }

  private void processRecord(GraphModel model, CompilationUnit cu, RecordDeclaration rec, MethodCallAnalyzer analyzer) {
    String pkg = extractPackage(cu);
    String fqn = buildFqn(pkg, rec.getName().asString());
    String module = PackageUtil.deriveModule(pkg);
    rec.getMethods().forEach(md -> analyzer.analyze(model, cu, fqn, pkg, module, md));
  }

  private String extractPackage(CompilationUnit cu) {
    return cu.getPackageDeclaration().map(pd -> pd.getName().toString()).orElse("");
  }

  private String buildFqn(String pkg, String className) {
    return pkg.isEmpty() ? className : pkg + "." + className;
  }
}