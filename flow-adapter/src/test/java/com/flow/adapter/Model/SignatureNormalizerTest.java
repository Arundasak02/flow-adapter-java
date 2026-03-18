package com.flow.adapter.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SignatureNormalizerTest {

  @Test
  void normalizesFullyQualifiedTypesToSimpleNames() {
    assertEquals("placeOrder(String):String",
        SignatureNormalizer.normalizeSignature("placeOrder(java.lang.String):java.lang.String"));

    assertEquals("kafkaTemplate():KafkaTemplate<String, String>",
        SignatureNormalizer.normalizeSignature(
            "kafkaTemplate():org.springframework.kafka.core.KafkaTemplate<java.lang.String, java.lang.String>"));

    assertEquals("foo(List<Order>):Optional<Result>",
        SignatureNormalizer.normalizeSignature(
            "foo(java.util.List<com.example.Order>):java.util.Optional<com.acme.Result>"));
  }
}

