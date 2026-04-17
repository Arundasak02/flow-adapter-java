package com.flow.adapter.Model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GraphModel {

  public String projectId, schema;
  public Map<String, MethodNode> methods = new LinkedHashMap<>();
  public Map<String, EndpointNode> endpoints = new LinkedHashMap<>();
  public Map<String, TopicNode> topics = new LinkedHashMap<>();
  public java.util.List<CallEdge> calls = new java.util.ArrayList<>();
  public java.util.List<EndpointEdge> endpointEdges = new java.util.ArrayList<>();
  public java.util.List<MessagingEdge> messaging = new java.util.ArrayList<>();

  public MethodNode ensureMethod(String id) {
    return methods.computeIfAbsent(id, k -> new MethodNode());
  }

  public EndpointNode ensureEndpoint(String id) {
    return endpoints.computeIfAbsent(id, k -> new EndpointNode());
  }

  public TopicNode ensureTopic(String name) {
    // Normalize name: callers might accidentally pass a name already prefixed with "topic:".
    String cleanName = (name != null && name.startsWith("topic:")) ? name.substring(6) : name;
    String id = "topic:" + cleanName;
    return topics.computeIfAbsent(id, k -> {
      TopicNode t = new TopicNode();
      t.id = id;
      t.name = cleanName;
      return t;
    });
  }

  // Helper to add messaging edges in a canonical way
  public void addMessagingEdge(String fromMethodId, String toTopicId, String kind) {
    MessagingEdge e = new MessagingEdge();
    e.from = fromMethodId;
    e.to = toTopicId;
    e.kind = kind;
    messaging.add(e);
  }

  public static class MethodNode {

    public String id, className, methodName, signature, packageName, moduleName;
    public Visibility visibility;
    public String methodBody;
    public java.util.List<String> annotations;
  }

  public static class EndpointNode {

    public String id, httpMethod, path;
    // allow multiple media types
    public java.util.List<String> produces;
    public java.util.List<String> consumes;
  }

  public static class TopicNode {

    public String id, name;
  }

  public static class CallEdge {

    public String from, to, kind = "calls";
  }

  public static class EndpointEdge {

    public String fromEndpoint, toMethod, kind = "handles";
  }

  public static class MessagingEdge {

    public String from, to, kind;
  }
}