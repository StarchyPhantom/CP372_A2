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
                    while (true) {
                        socket.send(out);
                        try {
                            DSPacket ack = receivePacket(socket);
                            if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
                                System.out.println("received ACK for seq " + seq);
                                seq = (seq + 1) % 128;
                                break;
                            }
                        } catch (SocketTimeoutException e) {
                            consecutiveTimeouts++;
                            System.out.println("timeout waiting for ACK " + seq + ", retransmitting");
                            if (consecutiveTimeouts >= 3) {
                                System.err.println("Unable to transfer file.");
                                System.exit(1);
                            }
                        }
                    }
                }

                int eotSeq = seq;
                DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
                DatagramPacket pout = new DatagramPacket(eot.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, rcvDataPort);
                int consecutiveTimeouts = 0;
                while (true) {
                    socket.send(pout);
                    try {
                        DSPacket ack = receivePacket(socket);
                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                            System.out.println("transfer complete");
                            break;
                        }
                    } catch (SocketTimeoutException e) {
                        consecutiveTimeouts++;
                        System.out.println("timeout waiting for EOT ACK, retransmitting");
                        if (consecutiveTimeouts >= 3) {
                            System.err.println("Unable to transfer file.");
                            System.exit(1);
                        }
                    }
                }
            } else {
                // Go-Back-N: chunk index 0..n-1, seq = (index+1)%128 (so 1,2,..,127,0)
                List<byte[]> chunks = readFileToChunks(file);
                int n = chunks.size();
                int eotSeq = (n + 1) % 128;

                int baseSeq = 1;
                int nextChunkIndex = 0;
                int consecutiveTimeouts = 0;
                DSPacket[] sentPackets = new DSPacket[128];

                while (baseSeq != eotSeq) {
                    while (nextChunkIndex < n && numUnackedSeq(baseSeq, nextChunkIndex) < windowSize) {
                        int seq = (nextChunkIndex + 1) % 128;
                        byte[] chunk = chunks.get(nextChunkIndex);
                        DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, chunk);
                        sentPackets[seq] = dataPkt;
                        nextChunkIndex++;
                    }
                    if (numUnackedSeq(baseSeq, nextChunkIndex) > 0) {
                        sendWindow(socket, addr, rcvDataPort, baseSeq, nextChunkIndex, sentPackets);
                    }

                    try {
                        DSPacket ack = receivePacket(socket);
                        if (ack.getType() == DSPacket.TYPE_ACK) {
                            int s = ack.getSeqNum();
                            int newBase = (s + 1) % 128;
                            if (ackAdvancesWindow(s, baseSeq, nextChunkIndex)) {
                                System.out.println("received ACK for seq " + newBase);
                                baseSeq = newBase;
                                consecutiveTimeouts = 0;
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        consecutiveTimeouts++;
                        System.out.println("timeout, retransmitting window from base " + baseSeq);
                        if (consecutiveTimeouts >= 3) {
                            System.err.println("Unable to transfer file.");
                            System.exit(1);
                        }
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

    /** Last sent seq for nextChunkIndex chunks (chunk 0..nextChunkIndex-1 -> seq 1,2,..,127,0). */
    private static int lastSentSeq(int nextChunkIndex) {
        if (nextChunkIndex == 0) return -1;
        return (nextChunkIndex % 128 == 0) ? 0 : (nextChunkIndex % 128);
    }

    /** Number of packets in flight: from baseSeq to last sent (by nextChunkIndex). */
    private static int numUnackedSeq(int baseSeq, int nextChunkIndex) {
        if (nextChunkIndex == 0) return 0;
        int last = lastSentSeq(nextChunkIndex);
        return (last - baseSeq + 128) % 128 + 1;
    }

    /** Whether ACK for seq s advances the window (s is in [baseSeq, lastSent] circular). */
    private static boolean ackAdvancesWindow(int s, int baseSeq, int nextChunkIndex) {
        if (nextChunkIndex == 0) return false;
        int last = lastSentSeq(nextChunkIndex);
        int dist = (s - baseSeq + 128) % 128;
        int windowLen = (last - baseSeq + 128) % 128 + 1;
        return dist < windowLen;
    }

    /** Send all packets from baseSeq to last chunk (nextChunkIndex-1) in permuted-by-4 order. */
    private static void sendWindow(DatagramSocket socket, InetAddress addr, int rcvDataPort,
                                   int baseSeq, int nextChunkIndex, DSPacket[] sentPackets) throws IOException {
        int count = numUnackedSeq(baseSeq, nextChunkIndex);
        List<DSPacket> inOrder = new ArrayList<>();
        int seq = baseSeq;
        for (int i = 0; i < count; i++) {
            inOrder.add(sentPackets[seq]);
            seq = (seq + 1) % 128;
        }
        List<DSPacket> toSend = new ArrayList<>();
        for (int i = 0; i < inOrder.size(); i += 4) {
            if (i + 4 <= inOrder.size()) {
                toSend.addAll(ChaosEngine.permutePackets(inOrder.subList(i, i + 4)));
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
