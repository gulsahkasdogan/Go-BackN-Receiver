
/*
* Programming Assignment 2 
* Reliable Transfer Protocol over UDP: Transfers specified file over a lossy network. 

* @author Elif Gulsah Kasdogan 
*/
import java.io.*;
import java.util.*;
import java.net.DatagramPacket; //UDP
import java.net.DatagramSocket; //UDP
import java.net.InetAddress;

public class Sender extends Thread {

    private static String filePath;
    private static int receiverPort;
    private static int windowSizeN;
    private static int retransmissionTimeout; // milliseconds
    private static int MAX_LENGTH = 1022;

    // Go-Back-N related
    private static int base = 1; // sequence number of packet sent but not yet acknowledged
    private static int nextSeqNumber = 1; // sequence number of the packet next to be sent
    private static boolean isDone = false; // true if we are done with reading file

    // timer
    private static boolean timeout = false;
    private static long startTime = 0; // for timer
    private static int elapsedTime = 0;
    private static boolean timerIsWorking = false;
    private static int lastACK = 0;

    // DatagramSocket
    static DatagramSocket senderSocket = null;
    static FileInputStream fileReader = null;

    private static int seqNo = 1; // for filling the packets
    private static LinkedList<byte[]> packets = new LinkedList<byte[]>(); // list of all packets
    private static InetAddress receiverIP = null; // 127.0.0.1 localhost

    // Threads 
    private static Thread senderThread = null; 
    private static Thread ackThread = null;

    // create two byte header for given sequence number
    public static byte[] createHeader(int seqNo) {
        // in Big-endian format 
        byte[] data = new byte[2];
        data[0] = (byte) ((seqNo >> 8) & 0xFF);
        data[1] = (byte) (seqNo & 0xFF);
        return data;
    }

    // create packet by appending data to header
    public static byte[] createPacket(byte[] data) {
        byte[] header = createHeader(seqNo);
        byte[] packet = new byte[data.length + header.length]; // data + header
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(data, 0, packet, header.length, data.length);
        seqNo = seqNo + 1;
        return packet;
    }

    // create all packets and fill packets list
    public static void createAllPackets() throws IOException {
        fileReader = new FileInputStream(filePath); // open file

        // // uncomment this part for checking
        // File outFile = new File("out");
        // FileOutputStream fos = new FileOutputStream(outFile);

        while (!isDone) {
            // while there are still bytes to read from file
            byte[] data = new byte[MAX_LENGTH];

            int bytesRead = fileReader.read(data);

            if (bytesRead == -1) {
                isDone = true;
                break;
            } else {
                byte[] packet = createPacket(data);
                packets.add(packet); // add packet to list of packets, its sequence num is index+1 because list is zero based

                // // uncomment this part for checking
                // byte[] temp = new byte[1022];
                // System.arraycopy(packet, 2, temp, 0, 1022); // out.png
                // fos.write(temp);
            }
        }

        //fos.close();
        fileReader.close();
    }

    // send the packet with specified sequence number
    public static void sendPacket(int sequenceNo) throws IOException {
        if (sequenceNo <= packets.size()) {
            // System.out.println("Sending packet: " + sequenceNo);
            byte[] packet = packets.get(sequenceNo - 1);
            DatagramPacket pkt = new DatagramPacket(packet, 1024, receiverIP, receiverPort);
            senderSocket.send(pkt);
        }
    }

    public static void retransmission() throws IOException {
        if (base == nextSeqNumber) {
            // wait for ack thread, you might be left behind
            delay();
        } else {
            if (lastACK != seqNo - 1) {
                startTimer();
                // send packets from base to nexSeqNumber-1
                for (int i = base; i <= base+windowSizeN; i++) {
                    sendPacket(i);
                }
                elapsedTime = 0;
            } else {
                timeout = false;
            }
        }

    }

    public static void sendPackets() throws IOException {
        senderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // process
                    while (true) {
                        if (nextSeqNumber < base + windowSizeN) {
                            // packet is within the window
                            sendPacket(nextSeqNumber); // send with nextSeqNumber
                            if (base == nextSeqNumber) {
                                startTimer();
                            }
                            nextSeqNumber++;
                        } else {
                            // Wait for main thread notification or timeout
                            sleep(retransmissionTimeout);
                            retransmission();
                        }
                    }

                } catch (InterruptedException | IOException e) {
                    // System.out.println("Sender Thread interrupted");
                }
            }
        });
        senderThread.start();
    }

    public static void receiveAcks(DatagramSocket senderSocket) throws IOException {
        ackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (lastACK == seqNo - 1)
                        break;

                    try {
                        byte[] buf = new byte[2];
                        DatagramPacket packet = new DatagramPacket(buf, 2);
                        senderSocket.receive(packet);
                        int receivedAck = ((buf[0] & 0xff) << 8) | (buf[1] & 0xff);
                        lastACK = receivedAck; // update last received ACK
                        base = receivedAck + 1;
                        if (base == nextSeqNumber) {
                            stopTimer();
                        } else {
                            startTimer();
                        }

                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        ackThread.start();
    }

    public static void startTimer() {
        // also resets timer
        // System.out.println("start timer, elapsed: " + elapsedTime);
        elapsedTime = 0;
        timerIsWorking = true;
        startTime = System.nanoTime() / 1000000; // ms
    }

    public static void stopTimer() {
        // System.out.println("stop timer, elapsed: " + elapsedTime);
        timerIsWorking = false;
        elapsedTime = 0;
    }

    public static void checkTimeout() {
        updateElapsedTime();
        if (elapsedTime >= retransmissionTimeout) {
            timeout = true;
        } else {
            timeout = false;
        }
    }

    // update Elapsed Time
    public static void updateElapsedTime() {
        long currentTime = System.nanoTime() / 1000000; // ms
        elapsedTime += currentTime - startTime;
    }

    public static void delay(){
        // Surprisingly the amount of time for two System.out.print calls are sufficient for threads to wait for updates 
        System.out.print("");
        System.out.print("");
    }


    public static void main(String[] args) throws IOException {
        // args format: java Sender <file_path> <receiver_port> <window_size_N> <retransmission_timeout>

        filePath = args[0];
        receiverPort = Integer.parseInt(args[1]);
        windowSizeN = Integer.parseInt(args[2]);
        retransmissionTimeout = Integer.parseInt(args[3]);

        receiverIP = InetAddress.getByName("127.0.0.1"); // localhost
        senderSocket = new DatagramSocket();

        createAllPackets();

        sendPackets();
        receiveAcks(senderSocket);

        while (lastACK != seqNo - 1) {
            // there are still packets to process
            delay();
            checkTimeout();
        }

        senderThread.interrupt();

        // wait for both threads to join 
        try {
            senderThread.join();
            ackThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if(senderThread != null && ackThread != null){
            // close socket if both threads are dead
            if(!senderThread.isAlive() && !ackThread.isAlive()){
                // send 00
                int last = 0;
                byte[] EOT = new byte[2];
                EOT[0] = (byte) ((last >> 8) & 0xFF);
                EOT[1] = (byte) (last & 0xFF);
                DatagramPacket pkt = new DatagramPacket(EOT, 2, receiverIP, receiverPort);
                senderSocket.send(pkt); 
                senderSocket.close();
            }     
        }
    }
}
