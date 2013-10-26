package net.entgaming.wc3connectd;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

import net.entgaming.wc3connect.WCUtil;

public class WCServer extends Thread {
	public static int PORT = 6112;
	
	DatagramSocket wc3Server;
	Database database;
	
	ArrayList<byte[]> trustedHosts;
	
	public static void main(String args[]) {
		Config.init("wc3connect.cfg");
		
		Database database = new Database();
		
		WCServer server = new WCServer(database);
		server.init();
		server.start();
	}
	
	public WCServer(Database database) {
		this.database = database;
		
		trustedHosts = new ArrayList<byte[]>();
		String[] array = Config.getString("trusted", "").split(" ");
		
		for(String str : array) {
			str = str.trim();
			
			if(!str.equals("")) {
				String[] parts = str.split("\\.");
				byte[] bytes = new byte[parts.length];
				
				for(int i = 0; i < parts.length; i++) {
					bytes[i] = (byte) Integer.parseInt(parts[i]);
				}
				
				trustedHosts.add(bytes);
			}
		}
	}
		
	public void init() {
		SocketAddress addr = new InetSocketAddress(Config.getInt("port", PORT));
		
		try {
			wc3Server = new DatagramSocket(addr);
		} catch(IOException ioe) {
			System.out.println("[WCServer] Error while binding UDP: " + ioe.getLocalizedMessage());
		}
	}
		
	public void run() {
		ByteBuffer buf = ByteBuffer.allocate(65536);
		byte[] pbuf = new byte[65536];
		DatagramPacket packet = new DatagramPacket(pbuf, 65536);
		
		while(true) {
			if(!wc3Server.isBound()) {
				try {
					Thread.sleep(5000);
				} catch(InterruptedException e) {}
				
				init();
			}
			
			try {
				wc3Server.receive(packet);
				
				byte[] address = packet.getAddress().getAddress();
				
				if(address.length != 4) {
					continue;
				}
				
				boolean trusted = false;
				
				for(byte[] host : trustedHosts) {
					if(address.length == host.length) {
						boolean match = true;
						
						for(int i = 0; i < host.length; i++) {
							if(host[i] != address[i]) {
								match = false;
								break;
							}
						}
						
						if(match) {
							trusted = true;
							break;
						}
					}
				}
				
				if(!trusted) {
					System.out.println("[WCServer] Untrusted from " + packet.getAddress().getHostAddress());
					continue;
				}
				
				ByteBuffer lbuf = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
				buf.clear();
				
				lbuf.order(ByteOrder.LITTLE_ENDIAN);
				buf.order(ByteOrder.LITTLE_ENDIAN);

				if(packet.getLength() < 21) continue;
				
				int header = WCUtil.unsignedByte(lbuf.get()); //W3GS header constant

				if(header != 247) {
					continue;
				}

				int identifier = WCUtil.unsignedByte(lbuf.get()); //packet type identifier

				if(identifier != 48) { //not GAMEINFO packet
					continue;
				}

				buf.put((byte) header);
				buf.put((byte) identifier);
				lbuf.getShort(); //read packet size short (actually little endian)
				buf.putShort((short) 0); //packet size; do later

				buf.put(address); //IP address; this is not part of W3GS protocol but we add because it's required to connect
				buf.putInt(lbuf.getInt()); //product ID
				buf.putInt(lbuf.getInt()); //version
				buf.putInt(lbuf.getInt()); //32-bit host counter
				buf.putInt(lbuf.getInt()); //unknown

				String gamename = WCUtil.getTerminatedString(lbuf);
				
				byte[] bytes = gamename.getBytes();
				buf.put(bytes);
				buf.put((byte) 0); //null terminator

				if(packet.getLength() < 21 + bytes.length + 2) continue;
				
				buf.put(lbuf.get()); //unknown

				byte[] array = WCUtil.getTerminatedArray(lbuf);
				buf.put(array); //StatString
				buf.put((byte) 0); //null terminator

				if(packet.getLength() < 21 + bytes.length + 2 + array.length + 22) {
					System.out.println(packet.getLength() + "," + (23 + bytes.length + 2 + array.length + 22));
					continue;
				}
				
				buf.putInt(lbuf.getInt()); //slots total
				buf.putInt(lbuf.getInt()); //game type
				buf.putInt(lbuf.getInt()); //unknown
				buf.putInt(lbuf.getInt()); //slots open
				buf.putInt(lbuf.getInt()); //up time
				
				int port = WCUtil.unsignedShort(lbuf.getShort());
				buf.putShort((short) port); //port

				//assign length in little endian
				int length = buf.position();
				buf.putShort(2, (short) length);

				//get bytes
				byte[] packetBytes = new byte[length];
				buf.position(0);
				buf.get(packetBytes);

				System.out.println("[WCServer] Got packet from " + packet.getAddress().getHostAddress() + ":" + port);
				database.update(packet.getAddress().getHostAddress(), port, packetBytes);
			} catch(IOException ioe) {
				ioe.printStackTrace();
			}
		}
	}
}
