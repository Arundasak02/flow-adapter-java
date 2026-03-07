package com.flow.adapter.util;

import com.flow.adapter.Model.Visibility;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

public class VisibilityUtil {

  public static Visibility visibilityOf(MethodDeclaration md) {
    if (md.isPublic()) {
      return Visibility.PUBLIC;
    }
    if (md.isProtected()) {
      return Visibility.PROTECTED;
    }
    if (md.isPrivate()) {
      return Visibility.PRIVATE;
    }
    return Visibility.PACKAGE;
  }

  public static Visibility visibilityOf(ResolvedMethodDeclaration rmd) {
    AccessSpecifier as = rmd.accessSpecifier();
    if (as == AccessSpecifier.PUBLIC) {
      return Visibility.PUBLIC;
    }
    if (as == AccessSpecifier.PROTECTED) {
      return Visibility.PROTECTED;
    }
    if (as == AccessSpecifier.PRIVATE) {
      return Visibility.PRIVATE;
    }
    return Visibility.PACKAGE;
  }
}