package io.jeti.wireless;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class starts a {@link ServerSocket} on a new {@link Thread} and awaits
 * connections. All of the constructors require a non-null
 * {@link Server.Listener}, which is informed when:
 * <p>
 * <ol>
 * <li>The {@link ServerSocket} is opened (
 * {@link Server.Listener#onServerSocketOpened(int)})</li>
 * <li>A peer has connected ({@link Server.Listener#onSocketOpened(Socket)})
 * </li>
 * <li>The {@link ServerSocket} has been closed and we about to close all of the
 * connected {@link Socket}s due to either an {@link Exception} or a call to
 * {@link #stop()}) ({@link Server.Listener#preShutdown()}).</li>
 * <li>The {@link ServerSocket} and connected {@link Socket}s have all been
 * closed ( {@link Server.Listener#postShutdown()})</li>
 * </ol>
 * <p>
 * Note that {@link Server.Listener#onSocketOpened(Socket)} will be called each
 * time a peer connects, but the other methods will only be called once.
 */
public class Server {

    /**
     * A listener informed of some lifecycle events for the {@link Server}, such
     * as when the {@link ServerSocket} is opened, and when clients connect.
     */
    public interface Listener {

        /**
         * The {@link ServerSocket} has been opened on the specified port.
         */
        void onServerSocketOpened(int port);

        /**
         * A peer has connected and should be reachable through the specified
         * {@link Socket}.
         */
        void onSocketOpened(Socket socket);

        /**
         * The {@link ServerSocket} has been closed, and we are about to
         * explicitly close the other {@link Socket} connections. Be careful
         * what you do here because the other {@link Socket}s might have already
         * been closed elsewhere. This sequence of events is triggered when
         * either an {@link Exception} is thrown, or {@link #stop()} is called.
         */
        void preShutdown();

        /**
         * The {@link Server} has finished shutting down due to a call to
         * {@link Server#stop()}. The {@link ServerSocket} and connected
         * {@link Socket}s have all been closed.
         */
        void postShutdown();
    }

    /*
     * ---------------------------------------------
     *
     * Private Fields
     *
     * ---------------------------------------------
     */
    private ServerSocket        serverSocket = null;
    private final Object        serverLock   = new Object();

    private final Set<Socket>   sockets      = new HashSet<>();
    private static final int    portDefault  = 0;
    private final Thread        listeningThread;
    private final Listener      listener;
    private final AtomicBoolean running      = new AtomicBoolean(true);

    /*
     * ---------------------------------------------
     *
     * Constructors
     *
     * ---------------------------------------------
     */

    /**
     * Create and start a {@link Server} on an available port, and inform the
     * {@link Server.Listener} of the {@link Server}'s lifecycle, such as when
     * peers connect.
     */
    public static Server newInstance(Listener listener) {
        return newInstance(portDefault, listener);
    }

    /**
     * Create and start a {@link Server} on the specified port, and inform the
     * {@link Server.Listener} of the {@link Server}'s lifecycle, such as when
     * peers connect.
     */
    public static Server newInstance(final int port, final Listener listener) {
        Server server = new Server(port, listener);
        server.listeningThread.start();
        return server;
    }

    /**
     * Create a new {@link Thread} that will open the {@link ServerSocket} on
     * the specified port, and accept connections. The specified
     * {@link Server.Listener} will be informed of several lifecycle events,
     * such as when the {@link ServerSocket} is created, and when connections
     * are established.
     */
    private Server(final int port, final Listener listener) {

        this.listener = listener;
        if (this.listener == null) {
            throw new NullPointerException("The Listener cannot be null.");
        } else {
            listeningThread = new Thread(() -> {
                try {
                    synchronized (serverLock) {
                        if (running.get()) {
                            serverSocket = new ServerSocket(port);
                            listener.onServerSocketOpened(serverSocket.getLocalPort());
                        }
                    }
                    while (running.get()) {

                        /* Block until a peer connects or stop was called. */
                        final Socket socket = serverSocket.accept();

                        /*
                         * If you got to this point, then a peer accepted. If
                         * stop is called right now, we will not immediately
                         * fall through to the catch or finally block because
                         * the serverSocket is not interrupted while accepting.
                         * To be fair, we should let the listener know that a
                         * Socket was opened, even if we are just going to close
                         * it right away.
                         */
                        sockets.add(socket);

                        /*
                         * Note that we do not want this to occur on a different
                         * thread since that might result in a listener seeing
                         * the preShutdown or postShutdown call, and then a
                         * "socket opened" call after that. That would be very
                         * confusing.
                         */
                        listener.onSocketOpened(socket);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    /*
                     * The stop method is responsible for closing the
                     * serverSocket. We need to call stop here just in case an
                     * Exception occurred elsewhere in the code, such as when
                     * opening the ServerSocket, or due to one of the listener
                     * calls.
                     */
                    stop();
                    shutdown();
                }
            });
        }
    }

    /*
     * ---------------------------------------------
     *
     * Public Methods
     *
     * ---------------------------------------------
     */

    /**
     * Push the main {@link ServerSocket}'s listening thread into its finally
     * method by closing the {@link ServerSocket}. In the "finally" method, we
     * will {@link #shutdown()} all of the open {@link Socket} connections.
     */
    public void stop() {
        synchronized (serverLock) {
            running.set(false);
            close(serverSocket);
            serverSocket = null;
        }
    }

    /*
     * ---------------------------------------------
     *
     * Private Methods
     *
     * ---------------------------------------------
     */

    /**
     * Close all of the {@link Socket} connections opened by the {@link Server}.
     */
    private void shutdown() {
        listener.preShutdown();
        for (Socket socket : sockets) {
            close(socket);
        }
        sockets.clear();
        listener.postShutdown();
    }

    private void close(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}