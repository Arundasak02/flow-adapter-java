package com.flow.adapter.Model;

public enum Visibility {
  PUBLIC("public", "Public access"),
  PROTECTED("protected", "Protected access"),
  PRIVATE("private", "Private access"),
  PACKAGE("package", "Package-private access");

  private final String value;
  private final String description;

  Visibility(String value, String description) {
    this.value = value;
    this.description = description;
  }

  public String getValue() {
    return value;
  }

  public String getDescription() {
    return description;
  }

  public static Visibility fromString(String value) {
    for (Visibility v : values()) {
      if (v.value.equals(value)) {
        return v;
      }
    }
    return PACKAGE;
  }
}

