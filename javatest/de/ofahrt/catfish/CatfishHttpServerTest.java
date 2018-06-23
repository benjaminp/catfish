package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.HttpResponseWriter;

public class CatfishHttpServerTest {

  private static HttpRequest parse(String text) throws Exception {
  	byte[] data = text.getBytes("ISO-8859-1");
  	IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
  	int consumed = parser.parse(data);
  	assertEquals(data.length, consumed);
  	assertTrue("parser not done at end of input", parser.isDone());
  	return parser.getRequest();
  }

  private static HttpResponse createResponse(HttpRequest request) throws Exception {
    CatfishHttpServer server = new CatfishHttpServer(HttpServerListener.NULL);
    HttpVirtualHost host = new HttpVirtualHost.Builder()
        .exact("/index", new TestServlet())
        .build();
    server.addHttpHost("localhost", host);
    server.setCompressionAllowed(true);
    final AtomicReference<HttpResponse> writtenResponse = new AtomicReference<>();
    final AtomicReference<ByteArrayOutputStream> writtenOutput = new AtomicReference<>();
    HttpResponseWriter writer = new HttpResponseWriter() {
      @Override
      public void commitBuffered(HttpResponse response) {
        if (!writtenResponse.compareAndSet(null, response)) {
          throw new IllegalStateException("Already set!");
        }
      }

      @Override
      public OutputStream commitStreamed(HttpResponse response) throws IOException {
        if (!writtenResponse.compareAndSet(null, response)) {
          throw new IllegalStateException("Already set!");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!writtenOutput.compareAndSet(null, out)) {
          throw new IllegalStateException("Already set!");
        }
        return out;
      }
    };
    Connection connection = new Connection(
        new InetSocketAddress("127.0.0.1", 80), new InetSocketAddress("127.0.0.1", 1234), false);
    server.createResponse(connection, request, writer);
    ByteArrayOutputStream out = writtenOutput.get();
    return out == null ? writtenResponse.get() : writtenResponse.get().withBody(out.toByteArray());
  }

  private static HttpResponse createResponse(String text) throws Exception {
  	return createResponse(parse(text));
  }

  @Test
  public void headRequestToExistingUrl() throws Exception {
    HttpResponse response = createResponse("HEAD /index HTTP/1.1\nHost: localhost\n\n");
  	assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
  }

  @Test
  public void headRequestToNonExistentUrl() throws Exception {
    HttpResponse response = createResponse("HEAD /nowhere HTTP/1.1\nHost: localhost\n\n");
  	assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatusCode());
  }

  @Test
  public void nonClosingServletWorksWithCompression() throws Exception {
    HttpResponse response = createResponse("GET /index HTTP/1.1\nHost: localhost\nAccept-Encoding: gzip\n\n");
  	assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
  }

  @Test
  public void emptyPost() throws Exception {
    HttpResponse response = createResponse("POST /index HTTP/1.1\nHost: localhost\n\n");
  	assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
  }

  @Test
  public void postWithContent() throws Exception {
    String content =
          "-----------------------------12184522311670376405338810566\n" // 58+1
        + "Content-Disposition: form-data; name=\"a\"\n" // 40+1
        + "\n" // 0+1
        + "b\n" // 1+1
        + "-----------------------------12184522311670376405338810566--\n" // 60+1
        + "";
    assertEquals(164, content.getBytes(Charset.forName("ISO-8859-1")).length);
    HttpResponse response = createResponse(
  			"POST /index HTTP/1.1\nHost: localhost\n"
  			+ "Content-Type: multipart/form-data; boundary=---------------------------13751323931886145875850488035\n"
  			+ "Content-Length: 164\n"
  			+ "\n"
  			+ content);
  	assertEquals(HttpServletResponse.SC_OK, response.getStatusCode());
  }
}