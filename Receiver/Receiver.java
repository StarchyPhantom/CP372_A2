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
        // Track logical epoch (wrap count) for each sequence slot to disambiguate cycles.
        int currentEpoch = 0;
        int[] slotEpoch = new int[128];
        for (int i = 0; i < 128; i++) slotEpoch[i] = Integer.MIN_VALUE;

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

                        // Determine whether this seq belongs to the current epoch or the previous one.
                        int distFromExpected = (seq - expectedSeq + 128) % 128;
                        boolean isUpcoming = (distFromExpected < 64);
                        int pktEpoch = isUpcoming ? currentEpoch : currentEpoch - 1;

                        // If slot already belongs to pktEpoch and is delivered, it's a duplicate
                        if (slotEpoch[seq] == pktEpoch && delivered[seq]) {
                            System.out.println("duplicate seq=" + seq + " (already delivered), re-ACK " + ((expectedSeq - 1 + 128) % 128));
                            cumulativeAck = (expectedSeq - 1 + 128) % 128;
                            maybeSendAck(socket, cumulativeAck, host, port, ackCountRef, rn);
                        }
                        // If slot already buffered for same epoch, it's a duplicate buffered packet
                        else if (slotEpoch[seq] == pktEpoch && dataBuffer[seq] != null) {
                            System.out.println("duplicate out-of-order seq=" + seq + " (already buffered, waiting for seq=" + expectedSeq + ")");
                            // Try to flush any contiguous buffered packets for current epoch
                            while (slotEpoch[expectedSeq] == currentEpoch && dataBuffer[expectedSeq] != null) {
                                fos.write(dataBuffer[expectedSeq]);
                                System.out.println("writing data seq=" + expectedSeq);
                                delivered[expectedSeq] = true;
                                slotEpoch[expectedSeq] = currentEpoch;
                                dataBuffer[expectedSeq] = null;
                                expectedSeq = (expectedSeq + 1) % 128;
                                if (expectedSeq == 0) {
                                    currentEpoch++; // we've advanced into a new epoch
                                }
                            }
                            cumulativeAck = (expectedSeq - 1 + 128) % 128;
                            maybeSendAck(socket, cumulativeAck, host, port, ackCountRef, rn);
                        } else {
                            // New packet for pktEpoch
                            if (seq != expectedSeq) {
                                System.out.println("buffering out-of-order seq=" + seq + " (expected seq=" + expectedSeq + ") epoch=" + pktEpoch);
                            }
                            dataBuffer[seq] = Arrays.copyOf(pkt.getPayload(), pkt.getLength());
                            slotEpoch[seq] = pktEpoch;

                            // Flush any in-order buffered packets belonging to the current epoch
                            while (slotEpoch[expectedSeq] == currentEpoch && dataBuffer[expectedSeq] != null) {
                                fos.write(dataBuffer[expectedSeq]);
                                System.out.println("writing data seq=" + expectedSeq);
                                delivered[expectedSeq] = true;
                                dataBuffer[expectedSeq] = null;
                                expectedSeq = (expectedSeq + 1) % 128;
                                if (expectedSeq == 0) {
                                    currentEpoch++;
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

