import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

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

        InetAddress addr = InetAddress.getByName(rcvIp);
        // bind socket to our ack port so we can receive replies there
        DatagramSocket socket = new DatagramSocket(senderAckPort);
        socket.setSoTimeout(timeout);

        // handshake: send SOT (seq 0) until we get ACK0
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        byte[] buf = sot.toBytes();
        DatagramPacket pkt = new DatagramPacket(buf, buf.length, addr, rcvDataPort);

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

        // read file and send data packets sequentially
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] payload = new byte[DSPacket.MAX_PAYLOAD_SIZE];
            int bytesRead;
            int seq = 1;
            int lastAcked = 0;

            while ((bytesRead = fis.read(payload)) != -1) {
                byte[] data = new byte[bytesRead];
                System.arraycopy(payload, 0, data, 0, bytesRead);
                DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, data);
                DatagramPacket out = new DatagramPacket(dataPkt.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, rcvDataPort);

                // stop and wait for ack for this seq
                while (true) {
                    socket.send(out);
                    try {
                        DSPacket ack = receivePacket(socket);
                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
                            System.out.println("received ACK for seq " + seq);
                            lastAcked = seq;
                            seq = (seq + 1) % 128;
                            break;
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("timeout waiting for ACK " + seq + ", retransmitting");
                    }
                }
            }

            // send EOT with seq = next sequence number
            DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, seq, null);
            DatagramPacket pout = new DatagramPacket(eot.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, rcvDataPort);
            while (true) {
                socket.send(pout);
                try {
                    DSPacket ack = receivePacket(socket);
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
                        System.out.println("transfer complete");
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("timeout waiting for EOT ACK, retransmitting");
                }
            }
        }

        socket.close();
    }

    private static DSPacket receivePacket(DatagramSocket socket) throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt);
        return new DSPacket(pkt.getData());
    }
}
