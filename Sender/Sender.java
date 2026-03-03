import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * Simple UDP Sender for DS‑FTP.
 *
 * The implementation uses the stop‑and‑wait protocol
 * todo: add go-back-n.
 * 
 * Command line:
 *   java Sender <receiver-host> <receiver-port> <file-to-send>
 */
public class Sender {

    private static final int TIMEOUT_MS = 500; // retransmission timeout

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: java Sender <host> <port> <file>");
            System.exit(1);
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        File file = new File(args[2]);
        if (!file.exists() || !file.isFile()) {
            System.err.println("invalid file: " + file);
            System.exit(1);
        }

        InetAddress addr = InetAddress.getByName(host);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(TIMEOUT_MS);

        // handshake: send SOT (seq 0) until we get ACK0
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        byte[] buf = sot.toBytes();
        DatagramPacket pkt = new DatagramPacket(buf, buf.length, addr, port);

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
                DatagramPacket out = new DatagramPacket(dataPkt.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, port);

                // stop and wait for ack for this seq
                while (true) {
                    socket.send(out);
                    try {
                        DSPacket ack = receivePacket(socket);
                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
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
            DatagramPacket pout = new DatagramPacket(eot.toBytes(), DSPacket.MAX_PACKET_SIZE, addr, port);
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
