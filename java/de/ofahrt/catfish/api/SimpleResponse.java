package de.ofahrt.catfish.api;

final class SimpleResponse implements HttpResponse {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

  private final int statusCode;
  private final HttpHeaders headers;
  private final byte[] body;

  public SimpleResponse(HttpStatusCode statusCode, HttpHeaders headers, byte[] body) {
    this.statusCode = statusCode.getCode();
    this.headers = headers;
    this.body = body;
  }

  public SimpleResponse(HttpStatusCode statusCode, HttpHeaders headers) {
    this(statusCode, headers, EMPTY_BYTE_ARRAY);
  }

  public SimpleResponse(HttpStatusCode statusCode) {
    this(statusCode, HttpHeaders.NONE, EMPTY_BYTE_ARRAY);
  }

  @Override
  public int getStatusCode() {
    return statusCode;
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }

  @Override
  public byte[] getBody() {
    return body;
  }
}
