package net.entgaming.wc3connect;

import java.awt.Color;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class WCHost implements Runnable {
	WC3Connect main; //applet object
	List<GameInfo> games; //list of games that we have broadcasted; need to be decreated when we refresh

	ServerSocket server; //server socket to tunnel to actual host
	int serverPort = 7112; //port number to use for the server
	
	DatagramSocket udpSocket; //UDP broadcast socket
	List<SocketAddress> udpTargets; //list of addresses to broadcast UDP packets to
	List<byte[]> udpCached; //cached packets to send to udpTargets on flush

	int war3version = 26; //current Warcraft III version, used in game broadcasts
	int counter = 0; //game counter
	
	boolean terminated = false;
	ByteBuffer buf;

	public WCHost(WC3Connect main) {
		this.main = main;
		
		serverPort = Config.getInt("host_port", 7112);
		war3version = Config.getInt("wc3_version", 26);
		udpTargets = new ArrayList<SocketAddress>();
		
		try {
			String udpTargetsString = Config.getString("host_target", "both");
			
			if(udpTargetsString.equals("both") || udpTargetsString.equals("localhost")) {
				udpTargets.add(new InetSocketAddress(InetAddress.getLocalHost(), 6112));
			
				if(udpTargetsString.equals("both")) {
					udpTargets.add(new InetSocketAddress("255.255.255.255", 6112));
				}
			} else {
				udpTargets.add(new InetSocketAddress(udpTargetsString, 6112));
			}
		} catch(UnknownHostException uhe) {
			main.log("[WCHost] UDP broadcast target error: " + uhe.getLocalizedMessage());
		}
		
		games = new ArrayList<GameInfo>();
		buf = ByteBuffer.allocate(65536);
	}
	
	public void init() {
		main.log("[WCHost] Creating server socket...");
		
		try {
			server = new ServerSocket(serverPort);
		} catch(IOException ioe) {
			main.log("[WCHost] Error while initiating server socket: " + ioe.getLocalizedMessage());
			main.setColor(Color.RED);
			
			//increment port number and try again
			serverPort++;
			main.log("[WCHost] Trying again in three seconds on port: " + serverPort);
			new RestartThread().start();
			return;
		}

		main.log("[WCHost] Creating UDP socket...");
		try {
			udpSocket = new DatagramSocket();
		} catch(IOException ioe) {
			main.log("[WCHost] Error while initiating UDP socket: " + ioe.getLocalizedMessage());
			main.setColor(Color.ORANGE);
			return;
		}
		
		new Thread(this).start();
	}
	
	public void deinit() {
		terminated = true;
		
		try {
			server.close();
			udpSocket.close();
		} catch(IOException ioe) {}
	}
	
	public void decreateGame(int counter) {
		ByteBuffer lbuf = ByteBuffer.allocate(8);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		
		lbuf.put((byte) 247); //W3GS constant
		lbuf.put((byte) 51); //DECREATE
		lbuf.putShort((short) 8); //packet length
		
		lbuf.putInt(counter);
		
		try {
			for(SocketAddress udpTarget : udpTargets) {
				DatagramPacket packet = new DatagramPacket(lbuf.array(), 8, udpTarget);
				udpSocket.send(packet);
			}
		} catch(IOException ioe) {
			main.log("[WCHost] Decreate error: " + ioe.getLocalizedMessage());
		}
	}
	
	public void clearGames() {
		if(udpSocket == null) {
			games.clear();
			return;
		}
		
		synchronized(games) {
			for(GameInfo game : games) {
				decreateGame(game.uid);
			}
			
			games.clear();
		}
	}

	public boolean receivedUDP(ByteBuffer lbuf, String gamenameFilter, List<Integer> portFilter) {
		if(buf == null || udpSocket == null) {
			return false;
		}
		
		buf.clear(); //use buf to create our own packet
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		buf.order(ByteOrder.LITTLE_ENDIAN);

		lbuf.getShort(); //ignore header because this is from server directly
		lbuf.getShort(); //also ignore length

		buf.put((byte) 247); //W3GS
		buf.put((byte) 48); //GAMEINFO
		buf.putShort((short) 0); //packet size; do later

		byte[] addr = new byte[4];
		lbuf.get(addr); //lbuf is special packet that includes IP address (not in W3GS GAMEINFO but used for wc3connect)
		
		int productid = lbuf.getInt();
		buf.putInt(productid); //product ID
		lbuf.getInt(); //ignore version in packet
		buf.putInt(war3version); //version
		
		int hostCounter = lbuf.getInt(); //hostcounter
		buf.putInt(counter); //replace hostcounter with uid
		
		buf.putInt(lbuf.getInt()); //unknown

		String gamename = WCUtil.getTerminatedString(lbuf);
		
		//filter by gamename if desired
		if(gamenameFilter != null && gamenameFilter.length() > 4 && !gamename.contains(gamenameFilter)) {
			return false;
		}
		
		byte[] bytes = WCUtil.strToBytes(gamename);
		buf.put(bytes);
		buf.put((byte) 0); //null terminator

		buf.put(lbuf.get()); //unknown

		byte[] array = WCUtil.getTerminatedArray(lbuf);
		buf.put(array); //StatString
		buf.put((byte) 0); //null terminator
		
		buf.putInt(lbuf.getInt()); //slots total
		buf.putInt(lbuf.getInt()); //game type
		buf.putInt(lbuf.getInt()); //unknown
		buf.putInt(lbuf.getInt()); //slots open
		buf.putInt(lbuf.getInt()); //up time

		//get the sender's port, but use our own server's port
		int senderPort = WCUtil.unsignedShort(lbuf.getShort());
		buf.putShort((short) serverPort); //port
		
		//filter by port if desired
		if(portFilter != null && !portFilter.contains(senderPort)) {
			return false;
		}

		//assign length in little endian
		int length = buf.position();
		buf.putShort(2, (short) length);

		//get bytes
		byte[] packetBytes = new byte[length];
		buf.position(0);
		buf.get(packetBytes);
		
		//create new gameinfo
		GameInfo game = new GameInfo(counter++, addr, senderPort, hostCounter);
		
		synchronized(games) {
			games.add(game);
		}

		//cache the packet to later send on flush call
		main.log("[WCHost] Broadcasting with gamename [" + gamename + "]; version: " + war3version +
				"; productid: " + productid + "; senderport: " + senderPort);
		udpCached.add(packetBytes);
		
		return true;
	}
	
	public void flush() {
		//this function exists so that we send to each target sequentially
		//that way, if the client receives multiple targets, they won't get
		// duplicate packets until after they've received one copy of each game
		//since LAN is limited to 20-25 games, we probably will fill the LAN with
		// the first target that works instead of adding duplicates to the list
		
		//WARNING: this function is not thread safe, and assumes it is called separately from receivedUDP
		
		try {
			for(SocketAddress udpTarget : udpTargets) {
				for(byte[] packetBytes : udpCached) {
					DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, udpTarget);
					udpSocket.send(packet);
					
					//for some games, it's desirable that the games appear in the order that we send them
					//we sleep to attempt to get this order; it is of course not guaranteed
					try {
						Thread.sleep(10);
					} catch(InterruptedException e) {}
				}
			}
			
			udpCached.clear();
			main.setColor(Color.GREEN);
		} catch(IOException ioe) {
			ioe.printStackTrace();
			main.log("[WCHost] Error while broadcast UDP: " + ioe.getLocalizedMessage());
		}
	}
	
	public GameInfo searchGame(int uid) {
		synchronized(games) {
			for(GameInfo game : games) {
				if(game.uid == uid) {
					return game;
				}
			}
		}
		
		return null;
	}
	
	public void run() {
		while(!terminated) {
			try {
				Socket socket = server.accept();
				main.log("[WCHost] Receiving connection from " + socket.getInetAddress().getHostAddress());
				new WCConnection(this, socket);
				
				main.setColor(Color.BLUE);
			} catch(IOException ioe) {
				main.log("[WCHost] Error while accepting connection: " + ioe.getLocalizedMessage());
				main.setColor(Color.PINK);
				break;
			}
		}
	}
	
	//used if we failed to bind on a certain server port
	class RestartThread extends Thread {
		public void run() {
			try {
				Thread.sleep(3000);
			} catch(InterruptedException e) {}
			
			init();
		}
	}
}