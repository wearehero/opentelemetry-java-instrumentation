/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pulsar.v28;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.impl.TopicMessageImpl;

public class VirtualFieldStore {
  private static final VirtualField<Message<?>, Context> MSG_FIELD =
      VirtualField.find(Message.class, Context.class);
  private static final VirtualField<Producer<?>, StringTuple2> PRODUCER_FIELD =
      VirtualField.find(Producer.class, StringTuple2.class);
  private static final VirtualField<Consumer<?>, String> CONSUMER_FIELD =
      VirtualField.find(Consumer.class, String.class);

  private VirtualFieldStore() {}

  public static void inject(Message<?> instance, Context context) {
    if (instance instanceof TopicMessageImpl<?>) {
      TopicMessageImpl<?> topicMessage = (TopicMessageImpl<?>) instance;
      instance = topicMessage.getMessage();
    }
    if (instance != null) {
      MSG_FIELD.set(instance, context);
    }
  }

  public static void inject(Producer<?> instance, String serviceUrl, String topic) {
    PRODUCER_FIELD.set(instance, StringTuple2.create(serviceUrl, topic));
  }

  public static void inject(Consumer<?> instance, String serviceUrl) {
    CONSUMER_FIELD.set(instance, serviceUrl);
  }

  public static Context extract(Message<?> instance) {
    if (instance instanceof TopicMessageImpl<?>) {
      TopicMessageImpl<?> topicMessage = (TopicMessageImpl<?>) instance;
      instance = topicMessage.getMessage();
    }
    if (instance == null) {
      return Context.current();
    }
    Context ctx = MSG_FIELD.get(instance);
    return ctx == null ? Context.current() : ctx;
  }

  public static StringTuple2 extract(Producer<?> instance) {
    return PRODUCER_FIELD.get(instance);
  }

  public static String extract(Consumer<?> instance) {
    String brokerUrl = CONSUMER_FIELD.get(instance);
    return brokerUrl == null ? "unknown" : brokerUrl;
  }
}
