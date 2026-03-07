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
                        
                        if (delivered[seq]) {
                            cumulativeAck = (expectedSeq - 1 + 128) % 128;
                            maybeSendAck(socket, cumulativeAck, host, port, ackCountRef, rn);
                        } else if (dataBuffer[seq] != null) {
                            cumulativeAck = (expectedSeq - 1 + 128) % 128;
                            maybeSendAck(socket, cumulativeAck, host, port, ackCountRef, rn);
                        } else {
                            dataBuffer[seq] = Arrays.copyOf(pkt.getPayload(), pkt.getLength());
                            while (dataBuffer[expectedSeq] != null) {
                                fos.write(dataBuffer[expectedSeq]);
                                System.out.println("writing data seq=" + expectedSeq);
                                delivered[expectedSeq] = true;
                                dataBuffer[expectedSeq] = null;
                                expectedSeq = (expectedSeq + 1) % 128;
                                // On wrap (127 -> 0), clear delivered[] so the next cycle's
                                // seq 0..127 are not mistaken for duplicates. Do not clear
                                // dataBuffer so we keep any buffered packets for the new cycle.
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
                            maybeSendAck(socket, pkt.getSeqNum(), host, port, ackCountRef, rn);
                            socket.close(); // done
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
    }

    private static void sendAck(DatagramSocket socket, int seq, String host, int port) throws IOException {
        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);
        byte[] buf = ack.toBytes();
        DatagramPacket out = new DatagramPacket(buf, buf.length, InetAddress.getByName(host), port);
        socket.send(out);
    }
}

