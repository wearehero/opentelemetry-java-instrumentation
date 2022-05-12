/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pulsar;

import static io.opentelemetry.javaagent.instrumentation.pulsar.PulsarTelemetry.MESSAGE_ID;
import static io.opentelemetry.javaagent.instrumentation.pulsar.PulsarTelemetry.PRODUCER_NAME;
import static io.opentelemetry.javaagent.instrumentation.pulsar.PulsarTelemetry.PROPAGATOR;
import static io.opentelemetry.javaagent.instrumentation.pulsar.PulsarTelemetry.SERVICE_URL;
import static io.opentelemetry.javaagent.instrumentation.pulsar.PulsarTelemetry.TOPIC;
import static io.opentelemetry.javaagent.instrumentation.pulsar.PulsarTelemetry.TRACER;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.pulsar.info.ClientEnhanceInfo;
import io.opentelemetry.javaagent.instrumentation.pulsar.textmap.MessageTextMapSetter;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.ProducerImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.SendCallback;

public class ProducerImplInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.apache.pulsar.client.impl.ProducerImpl");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isConstructor().and(isPublic()),
        ProducerImplInstrumentation.class.getName() + "$ProducerImplConstructorAdviser");

    transformer.applyAdviceToMethod(
        isMethod()
            .and(named("sendAsync"))
            .and(takesArgument(1, named("org.apache.pulsar.client.impl.SendCallback"))),
        ProducerImplInstrumentation.class.getName() + "$ProducerSendAsyncMethodAdviser");
  }

  @SuppressWarnings("unused")
  public static class ProducerImplConstructorAdviser {

    @Advice.OnMethodExit
    public static void intercept(
        @Advice.This ProducerImpl<?> producer,
        @Advice.Argument(value = 0) PulsarClient client,
        @Advice.Argument(value = 1) String topic) {
      PulsarClientImpl pulsarClient = (PulsarClientImpl) client;
      String url = pulsarClient.getLookup().getServiceUrl();
      ClientEnhanceInfo info = new ClientEnhanceInfo(topic, url);
      ClientEnhanceInfo.setProducerEnhancedField(producer, info);
    }
  }

  @SuppressWarnings("unused")
  public static class ProducerSendAsyncMethodAdviser {

    @Advice.OnMethodEnter
    public static void before(
        @Advice.This ProducerImpl<?> producer,
        @Advice.Argument(value = 0) Message<?> m,
        @Advice.Argument(value = 1, readOnly = false) SendCallback callback,
        @Advice.Local(value = "otelScope") Scope scope) {
      ClientEnhanceInfo info = ClientEnhanceInfo.getProducerEnhancedField(producer);
      if (null == info) {
        scope = Scope.noop();
        return;
      }

      MessageImpl<?> message = (MessageImpl<?>) m;
      scope =
          TRACER
              .spanBuilder("Producer/sendAsync")
              .setParent(Context.current())
              .setSpanKind(SpanKind.PRODUCER)
              .setAttribute(TOPIC, info.topic)
              .setAttribute(SERVICE_URL, info.brokerUrl)
              .setAttribute(PRODUCER_NAME, producer.getProducerName())
              .startSpan()
              .makeCurrent();

      Context current = Context.current();
      PROPAGATOR.inject(current, message, MessageTextMapSetter.INSTANCE);

      callback = new SendCallbackWrapper(info.topic, current, message, callback);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(
        @Advice.Thrown Throwable t,
        @Advice.This ProducerImpl<?> producer,
        @Advice.Local(value = "otelScope") Scope scope) {
      ClientEnhanceInfo info = ClientEnhanceInfo.getProducerEnhancedField(producer);
      if (null == info || null == scope) {
        if (null != scope) {
          scope.close();
        }
        return;
      }

      Span span = Span.current();
      if (null != t) {
        span.recordException(t);
      }

      span.end();
      scope.close();
    }
  }

  public static class SendCallbackWrapper implements SendCallback {
    private static final long serialVersionUID = 1L;

    private final String topic;
    private final Context context;
    private final MessageImpl<?> message;
    private final SendCallback delegator;

    public SendCallbackWrapper(
        String topic, Context context, MessageImpl<?> message, SendCallback callback) {
      this.topic = topic;
      this.context = context;
      this.message = message;
      this.delegator = callback;
    }

    @Override
    public void sendComplete(Exception e) {
      SpanBuilder builder =
          TRACER
              .spanBuilder("Producer/Callback")
              .setParent(this.context)
              .setSpanKind(SpanKind.PRODUCER)
              .setAttribute(TOPIC, topic);

      // set message id
      if (e == null
          && null != message.getMessageId()
          && message.getMessageId() instanceof MessageIdImpl) {
        MessageIdImpl messageId = (MessageIdImpl) message.getMessageId();
        String midStr = messageId.getLedgerId() + ":" + messageId.getEntryId();
        builder.setAttribute(MESSAGE_ID, midStr);
      }

      Span span = builder.startSpan();

      try (Scope ignore = span.makeCurrent()) {
        this.delegator.sendComplete(e);
      } catch (Throwable t) {
        span.recordException(t);
        throw t;
      } finally {
        span.end();
      }
    }

    @Override
    public void addCallback(MessageImpl<?> msg, SendCallback scb) {
      this.delegator.addCallback(msg, scb);
    }

    @Override
    public SendCallback getNextSendCallback() {
      return this.delegator.getNextSendCallback();
    }

    @Override
    public MessageImpl<?> getNextMessage() {
      return this.delegator.getNextMessage();
    }

    @Override
    public CompletableFuture<MessageId> getFuture() {
      return this.delegator.getFuture();
    }
  }
}
