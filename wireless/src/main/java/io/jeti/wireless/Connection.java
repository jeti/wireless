package io.jeti.wireless;

import io.jeti.streams.SerializableStreamReader;
import io.jeti.streams.SerializableStreamReaderSink;
import io.jeti.streams.StreamReader;
import io.jeti.streams.StreamWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * <p>
 * This class handles bidirectional {@link Socket} communication from a
 * {@link Source}. If the {@link Source} does not provide a {@link Socket}
 * directly, then one is opened on a new {@link Thread}. Once a {@link Socket}
 * is obtained, new {@link Thread}s for reading and writing to the
 * {@link Socket} are constructed, where you can write things to the
 * {@link Socket} via the {@link #write(Object)} method, and you can read things
 * from the {@link Socket} by providing a non-null {@link StreamReader} in one
 * of the constructors.
 * </p>
 * <p>
 * Note that the {@link StreamReader#readOne(Object)} method should read AND
 * CONSUME a single object from the stream. So you should, for instance, pass a
 * {@link SerializableStreamReaderSink}, not a {@link SerializableStreamReader}.
 * <p>
 * Furthermore, note that you can be informed of several {@link Connection}
 * lifecycle events by providing a {@link Listener} in one of the constructors.
 * </p>
 */
public class Connection {

    public interface Listener {
        void onSocketOpened(Connection connection, Socket socket);

        void onShutdown(Connection connection, Socket socket);
    }

    private final Source                source;
    private final StreamReader          reader;
    private final StreamWriter          writer;
    private final Listener              listener;
    private final BlockingQueue<Object> queue;

    private volatile boolean            reading;
    private volatile boolean            queuing;
    private volatile Thread             readingThread = null;
    private volatile Thread             writingThread = null;

    /**
     * This is the actual {@link Socket} on which reading and writing occurs. We
     * need this because not all of the {@link Source}'s explicitly provide a
     * {@link Socket}, in which case, we need to open one.
     */
    private Socket                      socket        = null;

    /**
     * A synchronization lock for the thread creation. This simply is just
     * important to cover the case where the user orders one of the stop
     * commands while a {@link Thread} is in the process of being created.
     */
    private final Object                threadLock    = new Object();

    /*
     * ---------------------------------------------
     *
     * Constructors
     *
     * ---------------------------------------------
     */
    private Connection(Source source, StreamReader reader, StreamWriter writer, Listener listener) {
        if (source == null) {
            throw new NullPointerException("The source cannot be null.");
        }
        if (reader == null && writer == null) {
            throw new NullPointerException(
                    "The reader and writer cannot both be null. There is no need for a connection in this case.");
        }
        this.source = source;
        this.reader = reader;
        this.writer = writer;
        this.listener = listener;
        this.queue = new LinkedBlockingQueue<>();
        this.reading = reader != null;
        this.queuing = writer != null;
    }

    /**
     * See {@link #start(Source, StreamReader, StreamWriter, Listener)} where
     * all of the unspecified inputs are assumed to be null.
     */
    public static Connection start(Source source, StreamReader reader) {
        return start(source, reader, null, null);
    }

    /**
     * See {@link #start(Source, StreamReader, StreamWriter, Listener)} where
     * all of the unspecified inputs are assumed to be null.
     */
    public static Connection start(Source source, StreamWriter writer) {
        return start(source, null, writer, null);
    }

    /**
     * See {@link #start(Source, StreamReader, StreamWriter, Listener)} where
     * all of the unspecified inputs are assumed to be null.
     */
    public static Connection start(Source source, StreamReader reader, Listener listener) {
        return start(source, reader, null, listener);
    }

    /**
     * See {@link #start(Source, StreamReader, StreamWriter, Listener)} where
     * all of the unspecified inputs are assumed to be null.
     */
    public static Connection start(Source source, StreamWriter writer, Listener listener) {
        return start(source, null, writer, listener);
    }

    /**
     * Create, start, and return a {@link Connection}.
     * <ul>
     * <li>The {@link Source} should define how to establish the connection. For
     * instance, the {@link Source} may contain a {@link Socket}, and IP
     * address, etc.</li>
     * <li>The {@link StreamReader} should define how objects are read from the
     * {@link Socket}'s {@link InputStream}. That is, the
     * {@link StreamReader#readOne(Object)} method should read AND CONSUME a
     * single object from the stream.</li>
     * <li>The {@link StreamWriter} should define how objects are written to the
     * {@link Socket}'s {@link OutputStream}.</li>
     * <li>The {@link Listener} will be informed of several {@link Connection}
     * lifecycle events.</li>
     * </ul>
     */
    public static Connection start(Source source, StreamReader reader, StreamWriter writer,
            Listener listener) {
        return new Connection(source, reader, writer, listener).start();
    }

    /*
     * ---------------------------------------------
     *
     * Private Methods and Classes
     *
     * ---------------------------------------------
     */
    private Connection start() {

        /*
         * Note that when the Socket is provided, we do not technically need to
         * create a separate Thread, but it is good to have the same behavior in
         * every case. If we do not create the following Thread, then
         * onSocketOpened() would be sometimes be called from the main thread,
         * and sometimes from this one.
         */
        new Thread(() -> {
            try {

                /* If necessary, open a Socket */
                if (source.socket != null) {
                    socket = source.socket;
                } else if (source.address != null) {
                    socket = new Socket(source.address, source.port);
                } else if (source.inetAddress != null) {
                    socket = new Socket(source.inetAddress, source.port);
                } else {
                    throw new IllegalArgumentException(
                            "We could not determine the Socket source");
                }

                /* Inform the listener of the opened Socket */
                if (listener != null) {
                    listener.onSocketOpened(Connection.this, socket);
                }

                /* Start the reading thread */
                if (reader != null) {
                    readingThread = threadStarter(() -> {
                        InputStream stream;
                        try {
                            stream = socket.getInputStream();
                            Object modifiedStream = reader.preLoop(stream);
                            while (reading) {
                                reader.readOne(modifiedStream);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            readingThreadFinally();
                        }
                    });
                } else {
                    readingThreadFinally();
                }

                /* Start the writing thread */
                if (writer != null) {
                    writingThread = threadStarter(() -> {
                        try {
                            OutputStream stream = socket.getOutputStream();
                            Object modifiedStream = writer.preLoop(stream);
                            while (writing()) {
                                writer.writeOne(modifiedStream, queue.take());
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            writingThreadFinally();
                        }
                    });
                } else {
                    writingThreadFinally();
                }

            } catch (Exception e) {

                e.printStackTrace();
                readingThreadFinally();
                writingThreadFinally();

            }
        }).start();
        return this;
    }

    private Thread threadStarter(Runnable runnable) {
        synchronized (threadLock) {
            if (queuing) {
                Thread thread = new Thread(runnable);
                thread.setPriority(Thread.MAX_PRIORITY);
                thread.start();
                return thread;
            } else {
                return null;
            }
        }
    }

    /**
     * The condition under which we keep writing objects.
     */
    private boolean writing() {
        return queuing || !queue.isEmpty();
    }

    /**
     * Shutdown the {@link Socket}'s input and {@link #attemptShutdown()}.
     */
    private void readingThreadFinally() {
        reading = false;
        try {
            socket.shutdownInput();
        } catch (IOException e) {
            e.printStackTrace();
        }
        attemptShutdown();
    }

    /**
     * Shutdown the {@link Socket}'s output and {@link #attemptShutdown()}.
     */
    private void writingThreadFinally() {
        queuing = false;
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            e.printStackTrace();
        }
        attemptShutdown();
    }

    /**
     * If both the input and output of the {@link Socket} have been shutdown,
     * then close the {@link Socket}.
     */
    private void attemptShutdown() {
        synchronized (threadLock) {
            if (socket.isInputShutdown() && socket.isOutputShutdown()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (listener != null) {
                    listener.onShutdown(this, socket);
                }
            }
        }
    }

    /*
     * ---------------------------------------------
     *
     * Stopping Methods
     *
     * ---------------------------------------------
     */
    /**
     * Finish writing all of the currently queued objects, and then stop
     * writing. Note that you cannot undo this operation. Furthermore, once the
     * reading and writing have both been stopped, the {@link Socket} will be
     * closed.
     */
    public void stopWritingAfterQueueEmptied() {
        queuing = false;
    }

    /**
     * If an object is in the process of being read, let it finish, and then
     * stop. Note that you cannot undo this operation. Furthermore, once the
     * reading and writing have both been stopped, the {@link Socket} will be
     * closed.
     */
    public void stopReading() {
        reading = false;
    }

    /**
     * If an object is in the process of being written, let it finish, and then
     * stop. Note that you cannot undo this operation. Furthermore, once the
     * reading and writing have both been stopped, the {@link Socket} will be
     * closed.
     */
    public void stopWriting() {
        stopWritingAfterQueueEmptied();
        queue.clear();
    }

    /**
     * Stop reading immediately. This may interrupt an object is in the process
     * of being read. Note that you cannot undo this operation. Furthermore,
     * once the reading and writing have both been stopped, the {@link Socket}
     * will be closed.
     */
    public void stopReadingImmediately() {
        stopReading();
        synchronized (threadLock) {
            if (readingThread != null) {
                readingThread.interrupt();
                readingThread = null;
            }
        }
    }

    /**
     * Stop writing immediately. This may interrupt an object is in the process
     * of being written. Note that you cannot undo this operation. Furthermore,
     * once the reading and writing have both been stopped, the {@link Socket}
     * will be closed.
     */
    public void stopWritingImmediately() {
        stopWriting();
        synchronized (threadLock) {
            if (writingThread != null) {
                writingThread.interrupt();
                writingThread = null;
            }
        }
    }

    /**
     * {@link #stopReadingImmediately()} and {@link #stopWritingImmediately()}.
     */
    public void close() {
        stopReadingImmediately();
        stopWritingImmediately();
    }

    /*
     * ---------------------------------------------
     *
     * Public Methods
     *
     * ---------------------------------------------
     */

    /**
     * Write an object to the {@link Connection}, returning a boolean indicating
     * whether the object was successfully queued to be written. This can fail
     * if the {@link Connection} is trying to shut down or if the
     * {@link BlockingQueue} used to queue objects ot be written is full.
     * Although we create a queue with a capacity of {@link Integer#MAX_VALUE},
     * it can still happen that you completely fill up the queue, notably if the
     * consumer is broken.
     */
    public boolean write(Object object) {
        if (queuing) {
            try {
                queue.put(object);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }
}
