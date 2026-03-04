import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

        int expectedSeq = 0; // for SOT we expect 0
        int lastAcked = -1;
        int ackCount = 0; // count of ACKs we intended to send (1-indexed)

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[BUFFER_SIZE];
            while (true) {
                DatagramPacket incoming = new DatagramPacket(buf, buf.length);
                socket.receive(incoming);
                DSPacket pkt = new DSPacket(incoming.getData());
                switch (pkt.getType()) {
                    case DSPacket.TYPE_SOT:
                        if (pkt.getSeqNum() == 0) {
                            System.out.println("received SOT seq=" + pkt.getSeqNum());
                            sendAck(socket, pkt.getSeqNum(), incoming.getAddress().getHostAddress(), incoming.getPort());
                            expectedSeq = 1;
                            lastAcked = 0;
                        }
                        break;
                    case DSPacket.TYPE_DATA:
                        int seq = pkt.getSeqNum();
                        if (seq == expectedSeq) {
                            fos.write(pkt.getPayload());
                            System.out.println("writing data seq=" + seq);
                            lastAcked = seq;
                            expectedSeq = (expectedSeq + 1) % 128;
                        } else {
                            System.out.println("ignored out-of-order seq=" + seq + " expected=" + expectedSeq);
                        }
                        sendAck(socket, lastAcked, incoming.getAddress().getHostAddress(), incoming.getPort());
                        break;
                    case DSPacket.TYPE_EOT:
                        if (pkt.getSeqNum() == expectedSeq) {
                            System.out.println("received EOT seq=" + pkt.getSeqNum());
                            sendAck(socket, pkt.getSeqNum(), incoming.getAddress().getHostAddress(), incoming.getPort());
                            socket.close(); // done
                            return;
                        } else {
                            // still ack last in-order
                            sendAck(socket, lastAcked, incoming.getAddress().getHostAddress(), incoming.getPort());
                        }
                        break;
                    default:
                        // ignore other types
                        break;
                }
            }
        }
    }

    private static void sendAck(DatagramSocket socket, int seq, String host, int port) throws IOException {
        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);
        byte[] buf = ack.toBytes();
        DatagramPacket out = new DatagramPacket(buf, buf.length, InetAddress.getByName(host), port);
        socket.send(out);
    }
}

