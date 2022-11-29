/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.servlet;

public class ServletResponseContext<T> {
  private final T response;
  // used for servlet 2.2 where request status can't be extracted from HttpServletResponse
  private Integer status;
  private Long timeout;

  public ServletResponseContext(T response) {
    this.response = response;
  }

  public T response() {
    return response;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public int getStatus() {
    return status;
  }

  public boolean hasStatus() {
    return status != null;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }

  public long getTimeout() {
    return timeout;
  }

  public boolean hasTimeout() {
    return timeout != null;
  }
}
