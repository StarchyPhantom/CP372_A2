import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * window size parameter is ignored for now, use it for go-back-N later lol
 *
 * Command line:
 *   java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
 */
public class Sender {

    private static final int TIMEOUT_MS = 500; // retransmission timeout

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            System.err.println("usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        String rcvIp = args[0];
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        File file = new File(args[3]);
        int timeout = Integer.parseInt(args[4]);
        int windowSize = 1; // default size
        if (args.length == 6) {
            windowSize = Integer.parseInt(args[5]);
        }
        if (!file.exists() || !file.isFile()) {
            System.err.println("invalid file: " + file);
            System.exit(1);
        }
        if (args.length == 6 && (windowSize % 4 != 0 || windowSize > 128)) {
            System.err.println("window_size must be a multiple of 4 and <= 128");
            System.exit(1);
        }

        InetAddress addr = InetAddress.getByName(rcvIp);
        // bind socket to our ack port so we can receive replies there
        DatagramSocket socket = new DatagramSocket(senderAckPort);
        socket.setSoTimeout(timeout);

        // handshake: send SOT (seq 0) until we get ACK0
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        byte[] buf = sot.toBytes();
        DatagramPacket pkt = new DatagramPacket(buf, buf.length, addr, rcvDataPort);

        long startTime = System.nanoTime();
        while (true) {
            socket.send(pkt);
            try {
                DSPacket ack = receivePacket(socket);
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("handshake complete");
                    break;
                }
            } catch (SocketTimeoutException e) {
                System.out.println("timeout waiting for SOT ACK, retransmitting");
            }
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            if (windowSize <= 1) {
                // Stop-and-Wait
                byte[] payload = new byte[DSPacket.MAX_PAYLOAD_SIZE];
                int bytesRead;
                int seq = 1;

                while ((bytesRead = fis.read(payload)) != -1) {
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(payload, 0, data, 0, bytesRead);
                    DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, data);
                    DatagramPacket out = new DatagramPacket(dataPkt.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, rcvDataPort);
                    
                    int consecutiveTimeouts = 0;
                    boolean retransmitted = false;
                    while (true) {
                        socket.send(out);
                        try {
                            DSPacket ack = receivePacket(socket);
                            if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
                                if (retransmitted) {
                                    System.out.println("retransmit recovered: received ACK for seq " + seq);
                                } else {
                                    System.out.println("received ACK for seq " + seq);
                                }
                                seq = (seq + 1) % 128;
                                break;
                            }
                        } catch (SocketTimeoutException e) {
                            consecutiveTimeouts++;
                            System.out.println("timeout waiting for ACK " + seq + ", retransmitting (attempt " + consecutiveTimeouts + "/3)");
                            if (consecutiveTimeouts >= 3) {
                                System.err.println("Unable to transfer file.");
                                System.exit(1);
                            }
                            retransmitted = true;
                        }
                    }
                }

                int eotSeq = seq;
                DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
                DatagramPacket pout = new DatagramPacket(eot.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, rcvDataPort);
                while (true) {
                    socket.send(pout);
                    try {
                        DSPacket ack = receivePacket(socket);
                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                            System.out.println("transfer complete");
                            break;
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("timeout waiting for EOT ACK, retransmitting");
                    }
                }
            } else {
                // Go-Back-N: chunk index 0..n-1, seq = (index+1)%128 (so 1,2,..,127,0)
                List<byte[]> chunks = readFileToChunks(file);
                int n = chunks.size();
                int eotSeq = (n % 128 == 0) ? 1 : (n % 128 + 1);

                int baseChunkIndex = 0;  // oldest unacked chunk index
                int nextChunkIndex = 0;  // next chunk to send for the first time
                int consecutiveTimeouts = 0;
                boolean retransmitted = false;

                while (baseChunkIndex < n) {
                    // Send only NEW packets that now fit in the window
                    int prevNext = nextChunkIndex;
                    while (nextChunkIndex < n && (nextChunkIndex - baseChunkIndex) < windowSize) {
                        nextChunkIndex++;
                    }
                    if (nextChunkIndex > prevNext) {
                        int firstNewSeq = (prevNext + 1) % 128;
                        int lastNewSeq  = (nextChunkIndex) % 128;
                        System.out.println("sending packets seq " + firstNewSeq + " to " + lastNewSeq
                                + " (chunks " + prevNext + "-" + (nextChunkIndex - 1) + ")");
                        sendChunkRange(socket, addr, rcvDataPort, prevNext, nextChunkIndex, chunks);
                    }

                    // Drain ACKs for up to timeout ms; stop as soon as window advances
                    long ackDeadline = System.currentTimeMillis() + timeout;
                    boolean advanced = false;
                    while (System.currentTimeMillis() < ackDeadline && !advanced) {
                        int remaining = (int) (ackDeadline - System.currentTimeMillis());
                        if (remaining <= 0) break;
                        socket.setSoTimeout(Math.max(1, remaining));
                        try {
                            DSPacket ack = receivePacket(socket);
                            if (ack.getType() == DSPacket.TYPE_ACK) {
                                int s = ack.getSeqNum();
                                int firstSeqInWindow = (baseChunkIndex + 1) % 128;
                                int offset = (s - firstSeqInWindow + 128) % 128;
                                int ackedChunk = baseChunkIndex + offset;
                                int newBase = ackedChunk + 1;
                                if (newBase > baseChunkIndex && ackedChunk < nextChunkIndex) {
                                    if (retransmitted) {
                                        System.out.println("retransmit recovered: received ACK for seq " + s);
                                        retransmitted = false;
                                    } else {
                                        System.out.println("received ACK for seq " + s);
                                    }
                                    baseChunkIndex = newBase;
                                    consecutiveTimeouts = 0;
                                    advanced = true;
                                }
                            }
                        } catch (SocketTimeoutException e) {
                            break;
                        }
                    }
                    socket.setSoTimeout(timeout);
                    if (!advanced) {
                        consecutiveTimeouts++;
                        int baseSeq = (baseChunkIndex + 1) % 128;
                        int lastSeq  = nextChunkIndex % 128;
                        System.out.println("timeout (attempt " + consecutiveTimeouts + "/3) — "
                                + "retransmitting window: seq " + baseSeq + " to " + lastSeq
                                + " (chunks " + baseChunkIndex + "-" + (nextChunkIndex - 1) + ")");
                        if (consecutiveTimeouts >= 3) {
                            System.err.println("Unable to transfer file.");
                            System.exit(1);
                        }
                        // Retransmit the entire current window from base
                        sendWindowFromChunks(socket, addr, rcvDataPort, baseChunkIndex, nextChunkIndex, chunks);
                        retransmitted = true;
                    }
                }

                DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
                DatagramPacket pout = new DatagramPacket(eot.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, rcvDataPort);
                while (true) {
                    socket.send(pout);
                    try {
                        DSPacket ack = receivePacket(socket);
                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                            System.out.println("transfer complete");
                            break;
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("timeout waiting for EOT ACK, retransmitting");
                    }
                }
            }
        }

        long elapsedNs = System.nanoTime() - startTime;
        System.out.printf("Total Transmission Time: %.2f seconds%n", elapsedNs / 1_000_000_000.0);
        socket.close();
    }

    private static DSPacket receivePacket(DatagramSocket socket) throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt);
        return new DSPacket(pkt.getData());
    }

    private static List<byte[]> readFileToChunks(File file) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        byte[] buf = new byte[DSPacket.MAX_PAYLOAD_SIZE];
        try (FileInputStream fis = new FileInputStream(file)) {
            int n;
            while ((n = fis.read(buf)) != -1) {
                chunks.add(java.util.Arrays.copyOf(buf, n));
            }
        }
        return chunks;
    }

    /** Send only the range [fromIndex, toIndex) of chunks (new packets), permuted in groups of 4. */
    private static void sendChunkRange(DatagramSocket socket, InetAddress addr, int rcvDataPort,
                                       int fromIndex, int toIndex, List<byte[]> chunks) throws IOException {
        List<DSPacket> inOrder = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            int seq = (i + 1) % 128;
            inOrder.add(new DSPacket(DSPacket.TYPE_DATA, seq, chunks.get(i)));
        }
        List<DSPacket> toSend = new ArrayList<>();
        for (int i = 0; i < inOrder.size(); i += 4) {
            if (i + 4 <= inOrder.size()) {
                toSend.addAll(ChaosEngine.permutePackets(new ArrayList<>(inOrder.subList(i, i + 4))));
            } else {
                toSend.addAll(inOrder.subList(i, inOrder.size()));
            }
        }
        for (DSPacket p : toSend) {
            DatagramPacket out = new DatagramPacket(p.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, rcvDataPort);
            socket.send(out);
        }
    }

    /** Retransmit full window [baseChunkIndex, nextChunkIndex) from chunks, permuted in groups of 4. */
    private static void sendWindowFromChunks(DatagramSocket socket, InetAddress addr, int rcvDataPort,
                                            int baseChunkIndex, int nextChunkIndex, List<byte[]> chunks) throws IOException {
        List<DSPacket> inOrder = new ArrayList<>();
        for (int i = baseChunkIndex; i < nextChunkIndex; i++) {
            int seq = (i + 1) % 128;
            inOrder.add(new DSPacket(DSPacket.TYPE_DATA, seq, chunks.get(i)));
        }
        List<DSPacket> toSend = new ArrayList<>();
        for (int i = 0; i < inOrder.size(); i += 4) {
            if (i + 4 <= inOrder.size()) {
                toSend.addAll(ChaosEngine.permutePackets(new ArrayList<>(inOrder.subList(i, i + 4))));
            } else {
                toSend.addAll(inOrder.subList(i, inOrder.size()));
            }
        }
        for (DSPacket p : toSend) {
            DatagramPacket out = new DatagramPacket(p.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, rcvDataPort);
            socket.send(out);
        }
    }
}
