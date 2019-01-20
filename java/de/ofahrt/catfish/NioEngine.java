package de.ofahrt.catfish;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.NetworkEventListener;

final class NioEngine {
  private static final boolean DEBUG = false;
  private static final boolean LOG_TO_FILE = false;

  private interface EventHandler {
    void handleEvent() throws IOException;
  }

  // Incoming data:
  // Socket -> SSL Stage -> HTTP Stage -> Request Queue
  // Flow control:
  // - Drop entire connection early if system overloaded
  // - Otherwise start in readable state
  // - Read data into parser, until request complete
  // - Queue full? -> Need to start dropping requests
  //
  // Outgoing data:
  // Socket <- SSL Stage <- HTTP Stage <- Response Stage <- AsyncBuffer <- Servlet
  // Flow control:
  // - Data available -> select on write
  // - AsyncBuffer blocks when the buffer is full

  interface Stage {
    void read() throws IOException;
    void write() throws IOException;
    void close();
  }

  interface Pipeline {
    Connection getConnection();
    void suppressWrites();
    void encourageWrites();
    void suppressReads();
    void encourageReads();
    void close();
    void queue(Runnable runnable);
    void log(String text, Object... params);
  }

  private interface LogHandler {
    void log(String text);
  }

  private final static class FileLogHandler implements LogHandler {
    private static final String POISON_PILL = "poison pill";

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);
    private final PrintWriter out;

    FileLogHandler(File f) throws IOException {
      out = new PrintWriter(new BufferedOutputStream(new FileOutputStream(f), 10000));
      new Thread(this::run, "log-writer").start();
    }

    @Override
    public void log(String text) {
      try {
        queue.put(text);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void run() {
      try {
        String line;
        while ((line = queue.take()) != POISON_PILL) {
          out.println(line);
        }
        out.close();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        out.close();
      }
    }
  }

  private final static class ConsoleLogHandler implements LogHandler {
    @Override
    public void log(String text) {
      System.out.println(text);
    }
  }

  private final class SocketHandler implements EventHandler, Pipeline {
    private final SelectorQueue queue;
    private final Connection connection;
    private final SocketChannel socketChannel;
    private final SelectionKey key;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private final LogHandler logHandler;

    private final Stage first;
    private boolean reading = true;
    private boolean writing;
    private boolean closed;

    SocketHandler(
        SelectorQueue queue,
        Connection connection,
        SocketChannel socketChannel,
        SelectionKey key,
        ServerHandler handler,
        LogHandler logHandler) {
      this.queue = queue;
      this.connection = connection;
      this.socketChannel = socketChannel;
      this.key = key;
      this.logHandler = logHandler;
      this.inputBuffer = ByteBuffer.allocate(4096);
      this.outputBuffer = ByteBuffer.allocate(4096);
      inputBuffer.clear();
      inputBuffer.flip(); // prepare for reading
      outputBuffer.clear();
      outputBuffer.flip(); // prepare for reading
      this.first = handler.connect(this, inputBuffer, outputBuffer);
    }

    @Override
    public Connection getConnection() {
      return connection;
    }

    private void updateSelector() {
      if (closed) {
        return;
      }
      key.interestOps(
          (reading ? SelectionKey.OP_READ : 0) | (writing ? SelectionKey.OP_WRITE : 0));
    }

    @Override
    public void suppressWrites() {
      if (!writing) {
        return;
      }
      writing = false;
      updateSelector();
    }

    @Override
    public void encourageWrites() {
      if (writing) {
        return;
      }
//      log("Writing");
      writing = true;
      updateSelector();
    }

    @Override
    public void suppressReads() {
      if (!reading) {
        return;
      }
      reading = false;
      updateSelector();
    }

    @Override
    public void encourageReads() {
      if (reading) {
        return;
      }
//      log("Reading");
      reading = true;
      updateSelector();
    }

    @Override
    public void handleEvent() {
      if (key.isReadable()) {
        try {
          inputBuffer.compact(); // prepare buffer for writing
          int readCount = socketChannel.read(inputBuffer);
          inputBuffer.flip(); // prepare buffer for reading
          if (readCount == -1) {
            log("Input closed");
            close();
          } else {
            log("Read %d bytes (%d buffered)",
                Integer.valueOf(readCount), Integer.valueOf(inputBuffer.remaining()));
            first.read();
          }
        } catch (IOException e) {
          networkEventListener.notifyInternalError(connection, e);
          close();
          return;
        }
      }
      if (key.isWritable()) {
        try {
          first.write();
          if (outputBuffer.hasRemaining()) {
            int before = outputBuffer.remaining();
            socketChannel.write(outputBuffer);
            log("Wrote %d bytes", Integer.valueOf(before - outputBuffer.remaining()));
            if (outputBuffer.remaining() > 0) {
              outputBuffer.compact(); // prepare for writing
              outputBuffer.flip(); // prepare for reading
            }
          } else {
            suppressWrites();
          }
        } catch (IOException e) {
          networkEventListener.notifyInternalError(connection, e);
          close();
          return;
        }
      }
      if (closed) {
        // Release resources, we may have a worker thread blocked on writing to the connection.
        first.close();
        closedCounter.incrementAndGet();
        log("Close");
        key.cancel();
        try {
          socketChannel.close();
        } catch (IOException ignored) {
          // There's nothing we can do if this fails.
          networkEventListener.notifyInternalError(connection, ignored);
        }
      }
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public void queue(Runnable runnable) {
      queue.queue(runnable);
    }

    @Override
    public void log(String text, Object... params) {
      if (DEBUG) {
        long atNanos = System.nanoTime() - connection.startTimeNanos();
        long atSeconds = TimeUnit.NANOSECONDS.toSeconds(atNanos);
        long nanoFraction = atNanos - TimeUnit.SECONDS.toNanos(atSeconds);
        String printedText = String.format(text, params);
        logHandler.log(
            String.format(
                "%s[%3s.%9d] %s",
                connection,
                Long.valueOf(atSeconds),
                Long.valueOf(nanoFraction),
                printedText));
      }
    }
  }

  private final class ServerSocketHandler implements EventHandler, Runnable {
    private final ServerSocketChannel serverChannel;
    private final SelectionKey key;
    private final ServerHandler handler;

    public ServerSocketHandler(ServerSocketChannel serverChannel, SelectionKey key, ServerHandler handler) {
      this.serverChannel = serverChannel;
      this.key = key;
      this.handler = handler;
    }

    @Override
    public void handleEvent() throws IOException {
      if (key.isAcceptable()) {
        @SuppressWarnings("resource")
        SocketChannel socketChannel = serverChannel.accept();
        openCounter.incrementAndGet();
        Connection connection = new Connection(
            (InetSocketAddress) socketChannel.socket().getLocalSocketAddress(),
            (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress(),
            handler.usesSsl());
        socketChannel.configureBlocking(false);
        socketChannel.socket().setTcpNoDelay(true);
        socketChannel.socket().setKeepAlive(true);
        socketChannel.socket().setSoLinger(false, 0);
        getQueueForConnection().attachConnection(connection, socketChannel, handler);
      }
    }

    @Override
    public void run() {
      key.cancel();
      try {
        serverChannel.close();
      } catch (IOException ignored) {
        // Not much we can do at this point.
      }
    }
  }

  interface ServerHandler {
    boolean usesSsl();
    Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer);
  }

  private final class SelectorQueue implements Runnable {
    private final int id;
    private final Selector selector;
    private final BlockingQueue<Runnable> eventQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> shutdownQueue = new LinkedBlockingQueue<>();
    private final LogHandler logHandler;
    private boolean shutdown;
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean();

    public SelectorQueue(int id, LogHandler logHandler) throws IOException {
      this.id = id;
      this.logHandler = logHandler;
      this.selector = Selector.open();
      Thread t = new Thread(this, "catfish-select-" + this.id);
      t.start();
    }

    private void listenPort(final InetAddress address, final int port, final ServerHandler handler) throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<IOException> thrownException = new AtomicReference<>();
      queue(() -> {
        try {
          if (shutdown) {
            return;
          }
          networkEventListener.portOpened(port, handler.usesSsl());
          @SuppressWarnings("resource")
          ServerSocketChannel serverChannel = ServerSocketChannel.open();
          serverChannel.configureBlocking(false);
          serverChannel.socket().setReuseAddress(true);
          serverChannel.socket().bind(new InetSocketAddress(address, port));
          SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
          ServerSocketHandler socketHandler = new ServerSocketHandler(serverChannel, key, handler);
          key.attach(socketHandler);
          shutdownQueue.add(socketHandler);
        } catch (IOException e) {
          thrownException.set(e);
        }
        latch.countDown();
      });
      latch.await();
      IOException e = thrownException.get();
      if (e != null) {
        throw e;
      }
    }

    private void shutdown() throws InterruptedException {
      if (!shutdownInitiated.getAndSet(true)) {
        throw new IllegalStateException();
      }
      final CountDownLatch latch = new CountDownLatch(1);
      shutdownQueue.add(() -> latch.countDown());
      queue(() -> shutdown = true);
      latch.await();
    }

    private void attachConnection(Connection connection, SocketChannel socketChannel, ServerHandler handler) {
      queue(() -> {
        try {
          SelectionKey socketKey = socketChannel.register(selector, SelectionKey.OP_READ);
          SocketHandler socketHandler =
              new SocketHandler(SelectorQueue.this, connection, socketChannel, socketKey, handler, logHandler);
          socketHandler.log("New");
          socketKey.attach(socketHandler);
        } catch (ClosedChannelException e) {
          throw new RuntimeException(e);
        }
      });
    }

    private void queue(Runnable runnable) {
      eventQueue.add(runnable);
      selector.wakeup();
    }

    @Override
    public void run() {
      try {
        while (!shutdown) {
//          if (DEBUG) {
//            System.out.println(
//                "PENDING: " + (openCounter.get() - closedCounter.get()) + " REJECTED " + rejectedCounter.get());
//          }
          selector.select();
//          if (DEBUG) {
//            System.out.printf(
//                "Queue=%d, Keys=%d\n", Integer.valueOf(id), Integer.valueOf(selector.keys().size()));
//          }
          Runnable runnable;
          while ((runnable = eventQueue.poll()) != null) {
            runnable.run();
          }
          for (SelectionKey key : selector.selectedKeys()) {
            EventHandler handler = (EventHandler) key.attachment();
            handler.handleEvent();
          }
          selector.selectedKeys().clear();
        }
        while (!shutdownQueue.isEmpty()) {
          shutdownQueue.remove().run();
        }
      } catch (IOException e) {
        networkEventListener.notifyInternalError(null, e);
      }
    }
  }

  private final NetworkEventListener networkEventListener;

  private final AtomicInteger openCounter = new AtomicInteger();
  private final AtomicInteger closedCounter = new AtomicInteger();

  private final SelectorQueue[] queues;
  private final AtomicInteger connectionIndex = new AtomicInteger();

  public NioEngine(NetworkEventListener networkEventListener) throws IOException {
    this.networkEventListener = networkEventListener;
    this.queues = new SelectorQueue[8];
    LogHandler logHandler;
    if (LOG_TO_FILE) {
      logHandler = new FileLogHandler(new File("/tmp/catfish.log"));
    } else {
      logHandler = new ConsoleLogHandler();
    }
    for (int i = 0; i < queues.length; i++) {
      queues[i] = new SelectorQueue(i, logHandler);
    }
  }

  public void listenAll(int port, ServerHandler handler) throws IOException, InterruptedException {
    listen(null, port, handler);
  }

  public void listenLocalhost(int port, ServerHandler handler) throws IOException, InterruptedException {
    listen(InetAddress.getLoopbackAddress(), port, handler);
  }

  private void listen(InetAddress address, int port, ServerHandler handler) throws IOException, InterruptedException {
    getQueueForConnection().listenPort(address, port, handler);
  }

  public void shutdown() throws InterruptedException {
    for (SelectorQueue queue : queues) {
      queue.shutdown();
    }
    networkEventListener.shutdown();
  }

  public int getOpenConnections() {
    return openCounter.get() - closedCounter.get();
  }

  SelectorQueue getQueueForConnection() {
    int index = mod(connectionIndex.getAndIncrement(), queues.length);
    return queues[index];
  }

  private int mod(int a, int b) {
    return ((a % b) + b) % b;
  }
}
