/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pulsar.v28

import io.opentelemetry.instrumentation.test.AgentInstrumentationSpecification
import io.opentelemetry.instrumentation.test.asserts.SpanAssert
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.Message
import org.apache.pulsar.client.api.MessageListener
import org.apache.pulsar.client.api.Producer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.Schema
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static io.opentelemetry.api.trace.SpanKind.CONSUMER
import static io.opentelemetry.api.trace.SpanKind.INTERNAL
import static io.opentelemetry.api.trace.SpanKind.PRODUCER

class PulsarClientTest extends AgentInstrumentationSpecification {

  private static final DockerImageName DEFAULT_IMAGE_NAME =
    DockerImageName.parse("apachepulsar/pulsar:2.8.0")

  @Shared
  private PulsarContainer pulsar
  @Shared
  private PulsarClient client
  @Shared
  private PulsarAdmin admin
  @Shared
  private Producer<String> producer
  @Shared
  private Consumer<String> consumer
  @Shared
  private Producer<String> producer1

  @Shared
  private String brokerUrl

  @Override
  def setupSpec() {
    PulsarContainer pulsar = new PulsarContainer(DEFAULT_IMAGE_NAME);
    pulsar.start()

    brokerUrl = pulsar.pulsarBrokerUrl
    client = PulsarClient.builder().serviceUrl(brokerUrl).build()
    admin = PulsarAdmin.builder().serviceHttpUrl(pulsar.httpServiceUrl).build()
  }

  @Override
  def cleanupSpec() {
    producer?.close()
    consumer?.close()
    producer1?.close()
    client?.close()
    admin?.close()
    pulsar.close()
  }

  def "test send non-partitioned topic"() {
    setup:
    def topic = "persistent://public/default/testNonPartitionedTopic_" + UUID.randomUUID()
    admin.topics().createNonPartitionedTopic(topic)
    producer =
      client.newProducer(Schema.STRING).topic(topic)
        .enableBatching(false).create()

    String msg = UUID.randomUUID().toString()

    def msgId
    runWithSpan("parent") {
      msgId = producer.send(msg)
    }

    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          name("parent")
          kind(INTERNAL)
          hasNoParent()
        }

        span(1) {
          name("PRODUCER/SEND")
          kind(PRODUCER)
          childOf span(0)
          attributes {
            "$SemanticAttributes.MESSAGING_SYSTEM" "pulsar"
            "$SemanticAttributes.MESSAGING_URL" brokerUrl
            "$SemanticAttributes.MESSAGING_DESTINATION_KIND" "topic"
            "$SemanticAttributes.MESSAGING_DESTINATION" topic
            "$SemanticAttributes.MESSAGING_MESSAGE_ID" msgId.toString()
            "$SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES" Long
          }
        }
      }
    }
  }

  def "test consume non-partitioned topic"() {
    setup:
    def topic = "persistent://public/default/testNonPartitionedTopic_" + UUID.randomUUID()
    admin.topics().createNonPartitionedTopic(topic)
    def latch = new CountDownLatch(1)
    consumer = client.newConsumer(Schema.STRING)
      .subscriptionName("test_sub")
      .topic(topic)
      .messageListener(new MessageListener<String>() {
        @Override
        void received(Consumer<String> consumer, Message<String> msg) {
          latch.countDown()
          consumer.acknowledge(msg)
        }
      })
      .subscribe()

    producer = client.newProducer(Schema.STRING)
      .topic(topic)
      .enableBatching(false)
      .create()

    def msgId
    def msg = UUID.randomUUID().toString()
    runWithSpan("parent") {
      msgId = producer.send(msg)
    }

    latch.await(1, TimeUnit.MINUTES)

    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          name("parent")
          kind(INTERNAL)
          hasNoParent()
        }

        span(1) {
          name("PRODUCER/SEND")
          kind(PRODUCER)
          childOf span(0)
          attributes {
            "$SemanticAttributes.MESSAGING_SYSTEM" "pulsar"
            "$SemanticAttributes.MESSAGING_DESTINATION_KIND" "topic"
            "$SemanticAttributes.MESSAGING_URL" brokerUrl
            "$SemanticAttributes.MESSAGING_DESTINATION" topic
            "$SemanticAttributes.MESSAGING_MESSAGE_ID" msgId.toString()
            "$SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES" Long
            "$SemanticAttributes.MESSAGE_TYPE" "NORMAL"
          }
        }

        span(2) {
          name("CONSUMER/RECEIVE")
          kind(CONSUMER)
          childOf span(1)
          attributes {
            "$SemanticAttributes.MESSAGING_SYSTEM" "pulsar"
            "$SemanticAttributes.MESSAGING_DESTINATION_KIND" "topic"
            "$SemanticAttributes.MESSAGING_URL" brokerUrl
            "$SemanticAttributes.MESSAGING_DESTINATION" topic
            "$SemanticAttributes.MESSAGING_MESSAGE_ID" msgId.toString()
            "$SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES" Long
          }
        }

        span(3) {
          name("CONSUMER/PROCESS")
          kind(INTERNAL)
          childOf(span(2))
          attributes {
            "$SemanticAttributes.MESSAGING_SYSTEM" "pulsar"
            "$SemanticAttributes.MESSAGING_DESTINATION_KIND" "topic"
            "$SemanticAttributes.MESSAGING_DESTINATION" topic
            "$SemanticAttributes.MESSAGING_MESSAGE_ID" msgId.toString()
          }
        }
      }
    }
  }


  def "test send partitioned topic"() {
    setup:
    def topic = "persistent://public/default/testPartitionedTopic_" + UUID.randomUUID()
    admin.topics().createPartitionedTopic(topic, 2)
    producer =
      client.newProducer(Schema.STRING).topic(topic)
        .enableBatching(false).create()

    String msg = UUID.randomUUID().toString()

    def msgId
    runWithSpan("parent") {
      msgId = producer.send(msg)
    }

    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          name("parent")
          kind(INTERNAL)
          hasNoParent()
        }

        span(1) {
          name("PRODUCER/SEND")
          kind(PRODUCER)
          childOf span(0)
          attributes {
            "$SemanticAttributes.MESSAGING_SYSTEM" "pulsar"
            "$SemanticAttributes.MESSAGING_DESTINATION_KIND" "topic"
            "$SemanticAttributes.MESSAGING_URL" brokerUrl
            "$SemanticAttributes.MESSAGING_DESTINATION".contains(topic)
            "$SemanticAttributes.MESSAGING_MESSAGE_ID" msgId.toString()
            "$SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES" Long
          }
        }
      }
    }
  }

  def "test consume partitioned topic"() {
    setup:
    def topic = "persistent://public/default/testPartitionedTopic_" + UUID.randomUUID()
    admin.topics().createPartitionedTopic(topic, 2)
    def latch = new CountDownLatch(1)
    consumer = client.newConsumer(Schema.STRING)
      .subscriptionName("test_sub")
      .topic(topic)
      .messageListener(new MessageListener<String>() {
        @Override
        void received(Consumer<String> consumer, Message<String> msg) {
          latch.countDown()
          consumer.acknowledge(msg)
        }
      })
      .subscribe()

    producer = client.newProducer(Schema.STRING)
      .topic(topic)
      .enableBatching(false)
      .create()

    def msgId
    def msg = UUID.randomUUID().toString()
    runWithSpan("parent") {
      msgId = producer.send(msg)
    }

    latch.await(1, TimeUnit.MINUTES)

    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          name("parent")
          kind(INTERNAL)
          hasNoParent()
        }

        span(1) {
          name("PRODUCER/SEND")
          kind(PRODUCER)
          childOf span(0)
          attributes {
            "$SemanticAttributes.MESSAGING_SYSTEM" "pulsar"
            "$SemanticAttributes.MESSAGING_DESTINATION_KIND" "topic"
            "$SemanticAttributes.MESSAGING_URL" brokerUrl
            "$SemanticAttributes.MESSAGING_DESTINATION".contains(topic)
            "$SemanticAttributes.MESSAGING_MESSAGE_ID" msgId.toString()
            "$SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES" Long
            "$SemanticAttributes.MESSAGE_TYPE" "NORMAL"
          }
        }

        span(2) {
          name("CONSUMER/RECEIVE")
          kind(CONSUMER)
          childOf span(1)
          attributes {
            "$SemanticAttributes.MESSAGING_SYSTEM" "pulsar"
            "$SemanticAttributes.MESSAGING_DESTINATION_KIND" "topic"
            "$SemanticAttributes.MESSAGING_URL" brokerUrl
            "$SemanticAttributes.MESSAGING_DESTINATION".contains(topic)
            "$SemanticAttributes.MESSAGING_MESSAGE_ID" msgId.toString()
            "$SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES" Long
          }
        }

        span(3) {
          name("CONSUMER/PROCESS")
          kind(INTERNAL)
          childOf(span(2))
          attributes {
            "$SemanticAttributes.MESSAGING_SYSTEM" "pulsar"
            "$SemanticAttributes.MESSAGING_DESTINATION_KIND" "topic"
            "$SemanticAttributes.MESSAGING_DESTINATION".contains(topic)
            "$SemanticAttributes.MESSAGING_MESSAGE_ID" msgId.toString()
          }
        }
      }
    }
  }


  def "test consume multi-topics"() {
    setup:

    def topic = "persistent://public/default/testNonPartitionedTopic_" + UUID.randomUUID()
    def topic1 = "persistent://public/default/testNonPartitionedTopic_" + UUID.randomUUID()
    producer = client.newProducer(Schema.STRING)
      .topic(topic)
      .enableBatching(false)
      .create()
    producer1 = client.newProducer(Schema.STRING)
      .topic(topic1)
      .enableBatching(false)
      .create()

    def latch = new CountDownLatch(2)

    runWithSpan("parent") {
      producer.send(UUID.randomUUID().toString())
      producer1.send(UUID.randomUUID().toString())
    }

    consumer = client.newConsumer(Schema.STRING)
      .topic(topic1, topic)
      .subscriptionName("test_sub")
      .messageListener(new MessageListener<String>() {
        @Override
        void received(Consumer<String> consumer, Message<String> msg) {
          latch.countDown()
          consumer.acknowledge(msg)
        }
      })
      .subscribe()

    latch.await(1, TimeUnit.MINUTES)
    if (latch.getCount() == 0) {
      assertTraces(1) {
        trace(0, 7) {
          span(0) {
            name("parent")
            kind(INTERNAL)
            hasNoParent()
          }

          def sendSpans = spans.findAll {
            it.name.equalsIgnoreCase("PRODUCER/SEND")
          }

          sendSpans.forEach {
            SpanAssert.assertSpan(it) {
              childOf(span(0))
            }
          }

          def receiveSpans = spans.findAll {
            it.name.equalsIgnoreCase("CONSUMER/RECEIVE")
          }

          def processSpans = spans.findAll {
            it.name.equalsIgnoreCase("CONSUMER/PROCESS")
          }

          receiveSpans.forEach {
            def parentSpanId = it.getParentSpanId()
            def parent = sendSpans.find {
              (it.spanId == parentSpanId)
            }

            SpanAssert.assertSpan(it) {
              childOf(parent)
            }
          }

          processSpans.forEach {
            def parentSpanId = it.getParentSpanId()
            def parent = processSpans.find {
              (it.spanId == parentSpanId)
            }

            SpanAssert.assertSpan(it) {
              childOf(parent)
            }
          }
        }
      }
    }
  }
}