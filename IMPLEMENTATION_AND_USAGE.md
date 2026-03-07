# DS-FTP: Implementation Summary & Usage

## What Was Implemented

### Receiver (`Receiver/Receiver.java`)

- **ChaosEngine integration**  
  Before every ACK (SOT, DATA, EOT), a 1-indexed `ackCount` is incremented; the ACK is sent only if `!ChaosEngine.shouldDrop(ackCount, RN)`. So when RN &gt; 0, every RN-th ACK is dropped as required.

- **GBN buffering and cumulative ACKs**  
  Single code path for both Stop-and-Wait and Go-Back-N:
  - `dataBuffer[128]` holds out-of-order DATA by sequence number.
  - On DATA arrival: if already delivered or already buffered, send cumulative ACK only; otherwise buffer payload, deliver in order (while `buffer[expectedSeq]` is set), then send one cumulative ACK = `(expectedSeq - 1) mod 128`.
  - SOT sets `expectedSeq = 1`; EOT is accepted when its seq equals `expectedSeq` and is ACKed (subject to ChaosEngine).

### Sender (`Sender/Sender.java`)

- **Transmission time**  
  Time from first SOT send to EOT ACK receipt is recorded and printed as:  
  `Total Transmission Time: X.XX seconds`

- **Critical failure**  
  If the same window times out 3 times in a row (no ACK that advances the window), the sender prints `Unable to transfer file.` and exits. The timeout counter resets whenever an ACK advances the window.

- **Stop-and-Wait (default)**  
  When `window_size` is omitted: one DATA packet at a time, wait for its ACK, retransmit on timeout, then send EOT with seq = next sequence number. Empty file: no DATA, EOT with seq 1 right after handshake.

- **Go-Back-N (when `window_size` is provided)**  
  - `window_size` must be a multiple of 4 and ≤ 128.
  - File is read into chunks (124 bytes each); chunk `i` uses seq `(i+1) % 128`.
  - State: `baseSeq` (oldest unacked), `nextChunkIndex` (next chunk to send). Window is refilled and sent (with **ChaosEngine.permutePackets** applied to each group of 4 consecutive packets). On cumulative ACK `s`, `baseSeq` advances to `(s+1) mod 128`. On timeout, the entire current window is retransmitted in the same permuted order; after 3 consecutive timeouts for the same window, critical failure.
  - After all data is ACKed, EOT is sent with seq = `(last DATA seq + 1) mod 128` and the sender waits for EOT ACK.

---

## How to Use

### Compile

```bash
cd Sender && javac *.java
cd ../Receiver && javac *.java
```

### Run

**1. Start the Receiver first** (it listens for data):

```text
java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
```

- `sender_ip` / `sender_ack_port`: where the receiver sends ACKs (sender’s address and ACK port).
- `rcv_data_port`: port the receiver listens on for DATA/SOT/EOT.
- `output_file`: path for the received file.
- `RN`: reliability number; 0 = no ACK loss, X = every X-th ACK dropped.

**2. Start the Sender** (sends the file):

```text
java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
```

- `rcv_ip` / `rcv_data_port`: receiver’s address and data port.
- `sender_ack_port`: port the sender listens on for ACKs.
- `input_file`: file to send.
- `timeout_ms`: retransmission timeout in milliseconds.
- `window_size`: **optional**. Omit for Stop-and-Wait; include for Go-Back-N (must be multiple of 4, ≤ 128).

### Example (same machine)

**Terminal 1 – Receiver:**

```bash
cd Receiver
java Receiver 127.0.0.1 5001 5000 out.bin 0
```

**Terminal 2 – Sender:**

Stop-and-Wait:

```bash
cd Sender
java Sender 127.0.0.1 5000 5001 input.bin 1000
```

Go-Back-N (e.g. window 8):

```bash
cd Sender
java Sender 127.0.0.1 5000 5001 input.bin 1000 8
```

Port roles:

- Receiver **listens** on `rcv_data_port` (e.g. 5000).
- Sender **listens** on `sender_ack_port` (e.g. 5001).
- Sender sends SOT/DATA/EOT to `rcv_ip:rcv_data_port`.
- Receiver sends ACKs to `sender_ip:sender_ack_port`.

### Verify

Compare input and output files (e.g. `cmp input.bin out.bin` or use a checksum). With RN = 0 they should match; with RN &gt; 0 the protocol retransmits so the transfer can still succeed.
