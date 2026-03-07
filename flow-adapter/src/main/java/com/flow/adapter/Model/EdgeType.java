package com.flow.adapter.Model;

public enum EdgeType {
  CALL("Method invokes another method"),
  HANDLES("Endpoint is handled by a method"),
  PRODUCES("Method produces a message to a topic"),
  CONSUMES("Method consumes a message from a topic"),
  DEFINES("Method is defined in a class"),
  BELONGS_TO("Class belongs to a service");

  private final String description;

  EdgeType(String description) {
    this.description = description;
  }

  public String getDescription() {
    return description;
  }
}

