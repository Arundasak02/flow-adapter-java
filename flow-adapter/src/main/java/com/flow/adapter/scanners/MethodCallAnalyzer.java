package com.flow.adapter.scanners;

import com.flow.adapter.Model.GraphModel;
import com.flow.adapter.util.PackageUtil;
import com.flow.adapter.util.SignatureUtil;
import com.flow.adapter.util.VisibilityUtil;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.MethodAmbiguityException;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;

public class MethodCallAnalyzer {

  private static final Logger logger = LoggerFactory.getLogger(MethodCallAnalyzer.class);

  /** Methods from java.lang.Object — never interesting architecturally. */
  private static final Set<String> OBJECT_METHODS = Set.of(
      "toString", "hashCode", "equals", "clone", "finalize", "wait", "notify", "notifyAll"
  );

  /**
   * External library package prefixes — calls into these never represent user business logic.
   * NOTE: org.springframework.* is intentionally NOT listed as a single prefix because user
   * applications can use packages like org.springframework.samples.* (e.g. Spring PetClinic).
   * Instead, specific Spring framework sub-packages are listed.
   */
  private static final Set<String> EXTERNAL_PREFIXES = Set.of(
      "java.", "javax.", "jakarta.", "sun.", "com.sun.",
      "org.springframework.boot.", "org.springframework.web.", "org.springframework.data.",
      "org.springframework.security.", "org.springframework.core.", "org.springframework.context.",
      "org.springframework.beans.", "org.springframework.cache.", "org.springframework.transaction.",
      "org.springframework.cloud.", "org.springframework.jdbc.", "org.springframework.aop.",
      "org.springframework.scheduling.", "org.springframework.validation.",
      "org.hibernate.", "org.junit.", "org.testng.", "org.mockito.",
      "org.hamcrest.", "org.assertj.", "io.micrometer.", "ch.qos.logback.", "org.slf4j."
  );

  private boolean isExternalClass(String qualifiedName) {
    return EXTERNAL_PREFIXES.stream().anyMatch(qualifiedName::startsWith);
  }

  /**
   * Returns true if the method is a getter, setter, or Object method.
   * These are architectural noise: they don't carry business logic or call flow.
   */
  private boolean isNoise(MethodDeclaration md) {
    String name = md.getNameAsString();
    if (OBJECT_METHODS.contains(name)) return true;
    if (name.length() > 3 && (name.startsWith("get") || name.startsWith("set") || name.startsWith("is"))) return true;
    return false;
  }

  public void analyze(GraphModel model, CompilationUnit cu, String fqn, String pkg, String module, MethodDeclaration md) {
    if (isNoise(md)) return;
    GraphModel.MethodNode node = createMethodNode(model, fqn, pkg, module, md);
    processMethodCalls(model, cu, md, node.id);
  }

  private GraphModel.MethodNode createMethodNode(GraphModel model, String fqn, String pkg, String module, MethodDeclaration md) {
    String sig = SignatureUtil.signatureOf(md);
    String id = fqn + "#" + sig;
    GraphModel.MethodNode node = model.ensureMethod(id);
    node.id = id;
    node.className = fqn;
    node.methodName = md.getNameAsString();
    node.signature = sig;
    node.packageName = pkg;
    node.moduleName = module;
    node.visibility = VisibilityUtil.visibilityOf(md);
    node.methodBody = md.toString();
    node.annotations = md.getAnnotations().stream()
        .map(a -> a.getNameAsString())
        .collect(Collectors.toList());
    return node;
  }

  private void processMethodCalls(GraphModel model, CompilationUnit cu, MethodDeclaration md, String callerId) {
    md.findAll(MethodCallExpr.class).forEach(call -> processMethodCall(model, cu, call, callerId));
  }

  private void processMethodCall(GraphModel model, CompilationUnit cu, MethodCallExpr call, String callerId) {
    try {
      ResolvedMethodDeclaration resolved = call.resolve();
      String declaringClass = resolved.declaringType().getQualifiedName();
      if (isExternalClass(declaringClass)) return;  // skip JDK/framework targets
      GraphModel.MethodNode target = createTargetMethodNode(model, resolved);
      addCallEdge(model, callerId, target.id);
    } catch (UnsolvedSymbolException | MethodAmbiguityException | IllegalStateException ex) {
      logger.warn("Could not resolve symbol for call in {}: {}",
          cu.getPrimaryTypeName().orElse("unknown"), ex.getMessage());
    } catch (Exception ex) {
      logger.error("Unexpected error while processing method call in {}: {}",
          cu.getPrimaryTypeName().orElse("unknown"), ex.getMessage(), ex);
    }
  }

  private GraphModel.MethodNode createTargetMethodNode(GraphModel model, ResolvedMethodDeclaration resolved) {
    String className = resolved.declaringType().getQualifiedName();
    String sig = SignatureUtil.signatureOf(resolved);
    String id = className + "#" + sig;

    GraphModel.MethodNode node = model.ensureMethod(id);
    node.id = id;
    node.className = className;
    node.methodName = resolved.getName();
    node.signature = sig;
    node.packageName = extractPackage(className);
    node.moduleName = PackageUtil.deriveModule(node.packageName);
    node.visibility = VisibilityUtil.visibilityOf(resolved);
    return node;
  }

  private String extractPackage(String className) {
    int idx = className.lastIndexOf('.');
    return idx > 0 ? className.substring(0, idx) : "";
  }

  private void addCallEdge(GraphModel model, String fromId, String toId) {
    GraphModel.CallEdge edge = new GraphModel.CallEdge();
    edge.from = fromId;
    edge.to = toId;
    model.calls.add(edge);
  }
}
