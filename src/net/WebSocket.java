package net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Just enough of RFC 6455 for the browser client: the handshake key and
 * text frames in both directions, with control frames (ping / pong /
 * close) answered as the RFC requires. Client-to-server frames are masked
 * (mandatory), server-to-client frames are not. Fragmented messages are
 * reassembled; binary frames are refused. Every message is one protocol
 * line, exactly what a raw socket client sends with a newline.
 */
final class WebSocket {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 64 * 1024;

    private WebSocket() {}

    /** Value of the {@code Sec-WebSocket-Accept} header for a client key. */
    static String acceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(sha1.digest((clientKey.trim() + GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Frames over a connected socket's streams. */
    static final class Transport implements ChessServer.Transport {
        private final InputStream in;
        private final OutputStream out;
        private boolean closeSent;

        Transport(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        /** Next text message, or null once the peer closed. Pings are answered here. */
        @Override
        public String read() throws IOException {
            ByteArrayOutputStream message = null;
            while (true) {
                int b0 = in.read();
                if (b0 < 0) return null;
                int b1 = in.read();
                if (b1 < 0) return null;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = ((long) readByte() << 8) | readByte();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | readByte(); }
                if (len > MAX_MESSAGE) throw new IOException("websocket frame too large");
                byte[] key = null;
                if (masked) { key = new byte[4]; readFully(key); }
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (key != null) for (int i = 0; i < payload.length; i++) payload[i] ^= key[i & 3];

                switch (opcode) {
                    case 0x8 -> {                                   // close: echo and finish
                        sendFrame(0x8, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0]);
                        return null;
                    }
                    case 0x9 -> sendFrame(0xA, payload);            // ping -> pong with the same payload
                    case 0xA -> { }                                 // pong: nothing to do
                    case 0x1, 0x0 -> {                              // text (or its continuation)
                        if (opcode == 0x1) message = new ByteArrayOutputStream();
                        if (message == null) throw new IOException("websocket continuation without start");
                        message.write(payload);
                        if (message.size() > MAX_MESSAGE) throw new IOException("websocket message too large");
                        if (fin) return message.toString(StandardCharsets.UTF_8);
                    }
                    default -> throw new IOException("unsupported websocket frame " + opcode);
                }
            }
        }

        @Override
        public void write(String line) throws IOException {
            sendFrame(0x1, line.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            if (!closeSent) {
                closeSent = true;
                try { sendFrame(0x8, new byte[]{0x03, (byte) 0xE8}); } catch (IOException ignored) { }   // 1000: normal
            }
        }

        private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
            frame.write(0x80 | opcode);
            if (payload.length < 126) {
                frame.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                frame.write(126);
                frame.write(payload.length >>> 8);
                frame.write(payload.length & 0xFF);
            } else {
                frame.write(127);
                for (int i = 7; i >= 0; i--) frame.write((int) (((long) payload.length >>> (8 * i)) & 0xFF));
            }
            frame.write(payload);
            out.write(frame.toByteArray());
            out.flush();
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException();
            return b;
        }

        private void readFully(byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
        }
    }
}
