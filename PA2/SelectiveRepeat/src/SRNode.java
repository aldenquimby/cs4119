import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.*;

public class SRNode {

    public static void main(String[] args) {
        SRNode node;

        try {
            // get input arguments
            int sourcePort = Integer.parseInt(args[0]);
            int destPort = Integer.parseInt(args[1]);
            int windowSize = Integer.parseInt(args[2]);
            int timeoutMs = Integer.parseInt(args[3]);
            double lossRate = Double.parseDouble(args[4]);

            // make a node, which validates inputs, and kick off SR
            node = new SRNode(sourcePort, destPort, windowSize, timeoutMs, lossRate);
        }
        catch (Exception e) {
            e.printStackTrace();
            System.err.println("Usage: SRNode <source-port> <destination-port> <window-size> <time-out> <loss-rate>");
            return;
        }

        node.Initialize();
    }

    public SRNode(int sourcePort, int destPort, int windowSize, int timeoutMs, double lossRate) throws IllegalArgumentException, SocketException {

        if (lossRate < 0 || lossRate > 1 || sourcePort <= 0 || destPort <= 0 || windowSize <= 0 || timeoutMs <= 0) {
            throw new IllegalArgumentException("Arguments outside valid range.");
        }

        this.sourcePort = sourcePort;
        this.destPort = destPort;
        this.windowSize = windowSize;
        this.timeoutMs = timeoutMs;
        this.lossRate = lossRate;

        this.socket = new DatagramSocket(sourcePort);

        this.sendNextSeqNum = 0;
        this.sendWindowBase = 0;
        this.ackedPackets = new HashSet<Integer>();
        this.queuedPackets = new ArrayList<Packet>();

        this.rcvWindowBase = 0;
        this.rcvdPackets = new HashMap<Integer, Packet>();
    }

    private int sourcePort;     // send msgs from this port
    private int destPort;       // send msgs to this port
    private int windowSize;     // length of the ACK window
    private int timeoutMs;      // packet timeout
    private double lossRate;    // packet loss rate
    private DatagramSocket socket;

    private int sendNextSeqNum;
    private int sendWindowBase;
    private HashSet<Integer> ackedPackets;
    private List<Packet> queuedPackets;

    private int rcvWindowBase;
    private HashMap<Integer, Packet> rcvdPackets;

    public void Initialize() {
        // listen for user input on another thread
        new Thread(new UserListener()).start();

        // listen for udp updates on this thread
        ListenForUpdates();
    }

    private class UserListener implements Runnable {

        @Override
        public void run() {
            // accept user input forever
            while (true) {
                String message = GetMessageFromUser();
                if (message == null) {
                    UnrecognizedInput();
                    continue;
                }
                SendMessage(message);
            }
        }

        private String GetMessageFromUser() {

            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

            String userInput;

            // read from std in
            try {
                userInput = br.readLine();
            } catch (IOException e) {
                return null;
            }

            // now make sure it is a valid "send" command, and parse out the message

            int commandSeparator = userInput.indexOf(' ');

            if (commandSeparator < 0) {
                return null;
            }

            String command = userInput.substring(0, commandSeparator);
            String message = userInput.substring(commandSeparator + 1);

            if (!"send".equals(command)) {
                return null;
            }

            return message;
        }

        private void UnrecognizedInput(){
            System.err.println("Oops, I don't recognize that command, try again.");
        }

    }

    private void ListenForUpdates() {
        // receive UDP messages forever
        while (true) {

            byte[] buffer = new byte[1024];
            DatagramPacket receivedDatagram = new DatagramPacket(buffer, buffer.length);

            try {
                socket.receive(receivedDatagram);
            }
            catch (IOException e) {
                continue; // just swallow this, received a weird packet
            }

            // simulate packet loss, done by receiver based on https://piazza.com/class#spring2013/csee4119/155
            if (new Random().nextDouble() < lossRate) {
                continue;
            }

            int fromPort = receivedDatagram.getPort();
            String msg = new String(buffer, 0, receivedDatagram.getLength()).trim();

            if (msg.startsWith("ACK")) {
                int packetNum;
                try {
                    packetNum = Integer.parseInt(msg.split(",")[1]);
                }
                catch (Exception e) {
                    continue; // this should never happen, invalid ACK message
                }
                HandleReceivedAck(packetNum);
            }
            else {
                Packet p = new Packet(msg, fromPort, sourcePort);
                HandleReceived(p);
            }
        }
    }

    private class TimeoutListener implements Runnable {

        private Packet payload;

        public TimeoutListener(Packet payload) {
            this.payload = payload;
        }

        @Override
        public void run() {
            // loop forever until packet is ACKed
            while (true) {

                // sleep for the timeout
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException e) {
                    // swallow this, if the thread is aborted we're screwed
                }

                // if it's been ACKed, we're done, otherwise print timeout
                if (ackedPackets.contains(payload.Number)) {
                    break;
                }
                else {
                    SenderPrinting.PrintTimeout(payload.Number);
                }

                // send it unreliably
                UnreliableSend(destPort, payload.toString());
                SenderPrinting.PrintSendPacket(payload.Number, payload.Data);
            }
        }
    }

    private class Packet {
        public final int SourcePort;
        public final int DestPort;
        public final String Data;
        public final int Number;

        public Packet(String data, int number, int sourcePort, int destPort) {
            Data = data;
            Number = number;
            SourcePort = sourcePort;
            DestPort = destPort;
        }

        public Packet(String pcktAsString, int sourcePort, int destPort) {
            SourcePort = sourcePort;
            DestPort = destPort;

            int separator = pcktAsString.indexOf('_');
            Number = Integer.parseInt(pcktAsString.substring(0, separator));
            Data = pcktAsString.substring(separator + 1);
        }

        @Override
        public String toString() {
            return Number + "_" + Data;
        }
    }

    private static class SenderPrinting {

        public static void PrintSendPacket(int packetNum, String data) {
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String toPrint = timestamp + " packet-" + packetNum + " " + data + " sent";
            System.out.println(toPrint);
        }

        // Receive Ack-1 refers to receiving the ack but no window advancement occurs
        public static void PrintAck1(int packetNum) {
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String toPrint = timestamp + " ACK-" + packetNum + " received";
            System.out.println(toPrint);
        }

        // window advancement occurs for Receive Ack-2, with starting/ending packet number of the window
        public static void PrintAck2(int packetNum, int windowStart, int windowEnd) {
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String toPrint = timestamp + " ACK-" + packetNum + " received; window = [" + windowStart + "," + windowEnd + "]";
            System.out.println(toPrint);
        }

        public static void PrintTimeout(int packetNum) {
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String toPrint = timestamp + " packet-" + packetNum + " timeout";
            System.out.println(toPrint);
        }

    }

    private static class ReceiverPrinting {

        public static void PrintReceive1(int packetNum, String data) {
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String toPrint = timestamp + " packet-" + packetNum + " " + data + " received";
            System.out.println(toPrint);
        }

        public static void PrintReceive2(int packetNum, String data, int windowStart, int windowEnd) {
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String toPrint = timestamp + " packet-" + packetNum + " " + data + " received; window = [" + windowStart + "," + windowEnd + "]";
            System.out.println(toPrint);
        }

        public static void PrintSendAck(int packetNum) {
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String toPrint = timestamp + " ACK-" + packetNum + " sent";
            System.out.println(toPrint);
        }

        public static void PrintDiscardPacket(int packetNum, String data) {
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String toPrint = timestamp + " packet-" + packetNum + " " + data + " discarded";
            System.out.println(toPrint);
        }

    }

    private void UnreliableSend(int toPort, String message) {
        try {
            // all communication is on the same machine, so use local host
            InetAddress receiverAddress = InetAddress.getLocalHost();
            byte[] buffer = message.getBytes();
            DatagramPacket sendDatagram = new DatagramPacket(buffer, buffer.length, receiverAddress, toPort);
            socket.send(sendDatagram);
        }
        catch (IOException e) {
            // swallow this, we will resend if needed
        }
    }

    private void HandleReceivedAck(int packetNum) {

        if (ackedPackets.contains(packetNum) || packetNum < sendWindowBase || packetNum >= sendWindowBase + windowSize) {
            // note, we can assume sender/receiver windows are the same so that this will never happen
            // see this post: https://piazza.com/class#spring2013/csee4119/152
            return;
        }

        // mark the packet as ACKed
        ackedPackets.add(packetNum);

        // if this is the first packet in the window, shift window and send more packets
        if (sendWindowBase == packetNum) {

            // shift the window up to the next unACKed packet
            while (ackedPackets.contains(sendWindowBase)) {
                sendWindowBase++;
            }

            // print the ACK2
            SenderPrinting.PrintAck2(packetNum, sendWindowBase, sendWindowBase + windowSize);

            // send all pending packets that are inside the new window
            while (!queuedPackets.isEmpty() && queuedPackets.get(0).Number < sendWindowBase + windowSize) {
                Packet nextPacketToSend = queuedPackets.remove(0);
                SendPacket(nextPacketToSend);
            }
        }
        else {
            // just print ACK1, don't move window or send anything new
            SenderPrinting.PrintAck1(packetNum);
        }

    }

    private void HandleReceived(Packet payload) {

        if (payload.Number >= rcvWindowBase + windowSize) {
            // this should never happen because we can assume sender/receiver windows are the same
            // see this post: https://piazza.com/class#spring2013/csee4119/152
            return;
        }

        // if the packet is before our window or we've received it, discard it
        if (payload.Number < rcvWindowBase || rcvdPackets.containsKey(payload.Number)) {
            ReceiverPrinting.PrintDiscardPacket(payload.Number, payload.Data);
        }
        else {
            // mark the packet received
            rcvdPackets.put(payload.Number, payload);

            // if this is the first packet in our window, shift window and deliver data (in theory)
            if (payload.Number == rcvWindowBase) {

                // shift the window up to the next packet we need
                while (rcvdPackets.containsKey(rcvWindowBase)) {
                    System.out.println("DELIVER DATA: " + rcvdPackets.get(rcvWindowBase).Data); // TODO remove this
                    rcvWindowBase++;
                }

                // print Receive2
                ReceiverPrinting.PrintReceive2(payload.Number, payload.Data, rcvWindowBase, rcvWindowBase + windowSize);
            }
            else {
                // just print Receive1, don't shift window or deliver data
                ReceiverPrinting.PrintReceive1(payload.Number, payload.Data);
            }
        }

        // send an ACK no matter what
        UnreliableSend(payload.SourcePort, "ACK," + payload.Number);
        ReceiverPrinting.PrintSendAck(payload.Number);

    }

    private void SendMessage(final String message) {

        // send one character at a time
        for (char c : message.toCharArray()) {

            Packet payload = new Packet(Character.toString(c), sendNextSeqNum, sourcePort, destPort);

            // if the window is full, save it for later
            if (sendNextSeqNum >= sendWindowBase + windowSize) {
                queuedPackets.add(payload);
            }
            else {
                SendPacket(payload);
            }

            // increment sequence number
            sendNextSeqNum++;
        }
    }

    private void SendPacket(final Packet payload) {
        SenderPrinting.PrintSendPacket(payload.Number, payload.Data);
        UnreliableSend(payload.DestPort, payload.toString());
        new Thread(new TimeoutListener(payload)).start();
    }

}
