package com.flow.adapter.util;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.util.stream.Collectors;

public class SignatureUtil {

  public static String signatureOf(MethodDeclaration md) {
    String params = md.getParameters().stream().map(p -> p.getType().toString())
        .collect(Collectors.joining(", "));
    String ret = md.getType().toString();
    return md.getNameAsString() + "(" + params + "):" + ret;
  }

  public static String signatureOf(ResolvedMethodDeclaration md) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < md.getNumberOfParams(); i++) {
      b.append(md.getParam(i).getType().describe());
      if (i < md.getNumberOfParams() - 1) {
        b.append(", ");
      }
    }
    String ret = md.getReturnType().describe();
    return md.getName() + "(" + b + "):" + ret;
  }
}