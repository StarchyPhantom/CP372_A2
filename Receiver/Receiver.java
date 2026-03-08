import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;


/**
 *
 * RN is for `ChaosEngine` btw
 *
 * Usage:
 *   java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
 */
public class Receiver {

    private static final int BUFFER_SIZE = DSPacket.MAX_PACKET_SIZE;

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            System.exit(1);
        }

        String senderIp = args[0];
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        File outFile = new File(args[3]);
        int rn = Integer.parseInt(args[4]);

        DatagramSocket socket = new DatagramSocket(rcvDataPort);
        System.out.println("listening for data on port " + rcvDataPort);

        int expectedSeq = 0; // for SOT we expect 0; after SOT, next DATA seq to deliver
        int[] ackCountRef = new int[]{0}; // 1-indexed count of ACKs we intended to send
        // GBN/SaW: buffer for out-of-order DATA; cumulative ACK = last contiguous delivered
        byte[][] dataBuffer = new byte[128][];
        boolean[] delivered = new boolean[128];

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[BUFFER_SIZE];
            while (true) {
                DatagramPacket incoming = new DatagramPacket(buf, buf.length);
                socket.receive(incoming);
                DSPacket pkt = new DSPacket(incoming.getData());
                String host = incoming.getAddress().getHostAddress();
                int port = incoming.getPort();
                switch (pkt.getType()) {
                    case DSPacket.TYPE_SOT:
                        if (pkt.getSeqNum() == 0) {
                            System.out.println("received SOT seq=" + pkt.getSeqNum());
                            maybeSendAck(socket, 0, host, port, ackCountRef, rn);
                            expectedSeq = 1;
                        }
                        break;
                    case DSPacket.TYPE_DATA:
                        int seq = pkt.getSeqNum();
                        int cumulativeAck;

                        // Determine whether this seq is "upcoming" (new cycle) or "past" (duplicate).
                        // A seq within 64 steps ahead of expectedSeq is upcoming; 64+ steps behind is past.
                        // This prevents seq 0 of cycle N+1 (arriving before the cycle-N wrap clears
                        // delivered[]) from being misidentified as a duplicate of cycle N's seq 0.
                        int distFromExpected = (seq - expectedSeq + 128) % 128;
                        boolean isUpcoming = (distFromExpected < 64);

                        if (!isUpcoming && delivered[seq]) {
                            // True duplicate from the current delivery context: already written to file
                            System.out.println("duplicate seq=" + seq + " (already delivered), re-ACK " + ((expectedSeq - 1 + 128) % 128));
                            cumulativeAck = (expectedSeq - 1 + 128) % 128;
                            maybeSendAck(socket, cumulativeAck, host, port, ackCountRef, rn);
                        } else if (!isUpcoming && dataBuffer[seq] != null) {
                            // Already buffered out-of-order from current window; try delivery defensively
                            System.out.println("duplicate out-of-order seq=" + seq + " (already buffered, waiting for seq=" + expectedSeq + ")");
                            while (dataBuffer[expectedSeq] != null) {
                                fos.write(dataBuffer[expectedSeq]);
                                System.out.println("writing data seq=" + expectedSeq);
                                delivered[expectedSeq] = true;
                                dataBuffer[expectedSeq] = null;
                                expectedSeq = (expectedSeq + 1) % 128;
                                if (expectedSeq == 0) {
                                    for (int i = 0; i < 128; i++) delivered[i] = false;
                                }
                            }
                            cumulativeAck = (expectedSeq - 1 + 128) % 128;
                            maybeSendAck(socket, cumulativeAck, host, port, ackCountRef, rn);
                        } else {
                            // New packet, or a cross-cycle packet whose seq was delivered in a prior cycle
                            if (seq != expectedSeq) {
                                System.out.println("buffering out-of-order seq=" + seq + " (expected seq=" + expectedSeq + ")");
                            }
                            dataBuffer[seq] = Arrays.copyOf(pkt.getPayload(), pkt.getLength());
                            while (dataBuffer[expectedSeq] != null) {
                                fos.write(dataBuffer[expectedSeq]);
                                System.out.println("writing data seq=" + expectedSeq);
                                delivered[expectedSeq] = true;
                                dataBuffer[expectedSeq] = null;
                                expectedSeq = (expectedSeq + 1) % 128;
                                // On wrap (127 -> 0), clear delivered[] so the next cycle's
                                // seqs are not misidentified as duplicates of the current cycle.
                                if (expectedSeq == 0) {
                                    for (int i = 0; i < 128; i++) delivered[i] = false;
                                }
                            }
                            cumulativeAck = (expectedSeq - 1 + 128) % 128;
                            maybeSendAck(socket, cumulativeAck, host, port, ackCountRef, rn);
                        }
                        break;
                    case DSPacket.TYPE_EOT:
                        if (pkt.getSeqNum() == expectedSeq) {
                            System.out.println("received EOT seq=" + pkt.getSeqNum());
                            int eotSeq = pkt.getSeqNum();
                            maybeSendAck(socket, eotSeq, host, port, ackCountRef, rn);
                            // Stay open for retransmits in case EOT ACK was dropped by ChaosEngine.
                            // Re-ACK any repeated EOT; exit when no more arrive within 3 timeouts.
                            socket.setSoTimeout(2000);
                            int eotRetries = 0;
                            byte[] eotBuf = new byte[DSPacket.MAX_PACKET_SIZE];
                            while (eotRetries < 3) {
                                DatagramPacket eotPkt = new DatagramPacket(eotBuf, eotBuf.length);
                                try {
                                    socket.receive(eotPkt);
                                    DSPacket rePkt = new DSPacket(eotPkt.getData());
                                    if (rePkt.getType() == DSPacket.TYPE_EOT && rePkt.getSeqNum() == eotSeq) {
                                        System.out.println("re-received EOT, re-sending ACK");
                                        maybeSendAck(socket, eotSeq,
                                                eotPkt.getAddress().getHostAddress(),
                                                eotPkt.getPort(), ackCountRef, rn);
                                        eotRetries = 0; // reset; sender clearly still waiting
                                    }
                                } catch (java.net.SocketTimeoutException ste) {
                                    eotRetries++;
                                }
                            }
                            socket.close();
                            return;
                        } else {
                            int lastInOrder = (expectedSeq - 1 + 128) % 128;
                            maybeSendAck(socket, lastInOrder, host, port, ackCountRef, rn);
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    /** Increment ackCount; send ACK only if ChaosEngine does not drop it. */
    private static void maybeSendAck(DatagramSocket socket, int seq, String host, int port,
                                     int[] ackCountRef, int rn) throws IOException {
        ackCountRef[0]++;
        if (!ChaosEngine.shouldDrop(ackCountRef[0], rn)) {
            sendAck(socket, seq, host, port);
        }
        else {
            System.out.println("simulating loss of ACK for seq=" + seq);
        }
    }

    private static void sendAck(DatagramSocket socket, int seq, String host, int port) throws IOException {
        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);
        byte[] buf = ack.toBytes();
        DatagramPacket out = new DatagramPacket(buf, buf.length, InetAddress.getByName(host), port);
        socket.send(out);
    }
}

