package io.jeti.wireless;

import java.net.InetAddress;
import java.net.Socket;

/**
 * A {@link Source} defines the root of a {@link Connection} object. This can be
 * defined as one of the following:
 * <ol>
 * <li>a {@link Socket}</li>
 * <li>an {@link InetAddress} and port</li>
 * <li>an IP address (specified as a String) and port</li>
 * </ol>
 */
public class Source {

    public final Socket      socket;
    public final InetAddress inetAddress;
    public final String      address;
    public final int         port;

    public Source(Socket socket) {
        this.socket = socket;
        this.inetAddress = null;
        this.address = null;
        this.port = 0;
    }

    public Source(InetAddress inetAddress, int port) {
        this.socket = null;
        this.inetAddress = inetAddress;
        this.address = null;
        this.port = port;
    }

    public Source(String address, int port) {
        this.socket = null;
        this.inetAddress = null;
        this.address = address;
        this.port = port;
    }
}
