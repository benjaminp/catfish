package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;

public interface HttpServerListener {
  public static final HttpServerListener NULL = new HttpServerListener() {
    @Override
    public void portOpened(int port, boolean ssl) {
    }

    @Override
    public void shutdown() {
    }
  };

  void portOpened(int port, boolean ssl);
  void shutdown();

  /**
   * @param connection
   * @param throwable
   */
  default void notifyInternalError(Connection connection, Throwable throwable) {
  }

  /**
   * @param connection
   * @param request
   * @param response
   */
  default void notifyRequest(Connection connection, HttpRequest request, HttpResponse response) {
  }
}