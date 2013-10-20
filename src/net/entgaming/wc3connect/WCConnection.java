package net.entgaming.wc3connect;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

import javax.swing.JOptionPane;

public class WCConnection {
	public static int PLAYERLEAVE_GPROXY = 100;
	
	public static byte[] EMPTYACTION = new byte[] {(byte) 0xF7, (byte) 0x0C, (byte) 0x06, 0, 0, 0};
	
	WCHost host;
	
	Timer timer;
	Socket localSocket;
	Socket remoteSocket;
	
	DataOutputStream localOut;
	Integer remoteSync;
	Integer localSync;
	DataOutputStream remoteOut;
	
	int sessionkey; //entry key to use to connect to server; GC uses spoofcheck so this should by 0
	GameInfo gameInfo; //host's game information, includes host counter and GCloud bot ID
	
	boolean terminated;
	
	//gproxy variables
	boolean gproxyEnabled;
	boolean gproxy; //whether we're using GProxy++; set to true only if we receive GPS_INIT packet from server
	boolean leaveGameSent; //whether player has already sent the leave game packet
	boolean gameStarted; //whether the game has started
	int pid; //our game PID
	boolean isSynchronized; //false if we are reconnecting to server
	
	int numEmptyActions; //number of empty actions server is using
	int numEmptyActionsUsed; //number we have sent for the last action packet
	boolean actionReceived; //whether we've received an action packet
	long lastActionTime; //last time we received action packet
	long lastConnectionAttemptTime; //last time we tried to connect to server
	
	int totalLocalPackets;
	int totalRemotePackets;
	Queue<byte[]> localBuffer; //local packet buffer
	
	ArrayList<Integer> laggers; //list of ID's of players currently lagging
	
	boolean remoteConnected;
	InetAddress remoteAddress;
	int remotePort;
	int remoteKey;
	
	public WCConnection(WCHost host, Socket socket) {
		this.host = host;
		this.localSocket = socket;
		sessionkey = (int) Config.getLong("connection_sessionkey", 0); //for unsigned int support
		gproxyEnabled = Config.getBoolean("connection_gproxy", true);
		
		gameInfo = null;
		terminated = false;
		
		//initialize GProxy++ settings
		remoteSync = new Integer(0);
		localSync = new Integer(0);
		gproxy = false;
		gameStarted = false;
		actionReceived = false;
		remoteConnected = false;
		leaveGameSent = false;
		isSynchronized = true;
		
		laggers = new ArrayList<Integer>();
		localBuffer = new LinkedList<byte[]>();
		
		try {
			localOut = new DataOutputStream(localSocket.getOutputStream());
		} catch(IOException ioe) {
			host.main.log("[ECConnection] Initialization error: " + ioe.getLocalizedMessage());
		}
		
		new ECForward(this, false, localSocket);
		
		//set up timer task
		timer = new Timer();
		timer.schedule(new EmptyActionTask(), 3000, 3000);
	}
	
	public synchronized void eventRemoteDisconnect(DataOutputStream currentRemoteOut) {
		//eventRemoteDisconnect will sometimes be triggered multiple times on the same socket
		//here we make sure that this is the first trigger
		//NOTE: actually this shouldn't be a problem anymore because all disconnect events
		// come from GCForward, but just to make sure we keep the if statement
		if(currentRemoteOut != remoteOut) {
			return;
		}
		
		synchronized(remoteSync) {
			if(remoteSocket != null && remoteSocket.isConnected()) {
				try {
					remoteSocket.close();
				} catch(IOException ioe) {}
			}
			
			remoteSocket = null;
			remoteOut = null;
		}
		
		if(gproxy && !leaveGameSent && actionReceived) {
			sendLocalChat("You have been disconnected from the server.");
			host.main.log("[ECConnection] You have been disconnected from the server.");
			
			//calculate time we have remaining to reconnect
			long timeRemaining = (numEmptyActions - numEmptyActionsUsed + 1) * 60 * 1000 - (System.currentTimeMillis() - lastActionTime);
			
			if(timeRemaining < 0) {
				timeRemaining = 0;
			}
			
			sendLocalChat("GProxy++ is attempting to reconnect... (" + (timeRemaining / 1000) + " seconds remain)");
			host.main.log("GProxy++ is attempting to reconnect... (" + (timeRemaining / 1000) + " seconds remain)");
			
			//update time
			lastConnectionAttemptTime = System.currentTimeMillis();
			
			//reconnect
			gproxyReconnect();
		} else {
			terminate();
		}
	}
	
	public synchronized void eventLocalDisconnect() {
		// ensure a leavegame message was sent, otherwise the server may wait for our reconnection which will never happen
		// if one hasn't been sent it's because Warcraft III exited abnormally
		synchronized(remoteSync) {
			if(!leaveGameSent && remoteOut != null) {
				leaveGameSent = true;
				
				try {
					ByteBuffer buf = ByteBuffer.allocate(8);
					buf.order(ByteOrder.LITTLE_ENDIAN);
					buf.put((byte) 0xF7);
					buf.put((byte) 0x21);
					buf.put((byte) 0x08);
					buf.put((byte) 0x00);
					buf.putInt(PLAYERLEAVE_GPROXY);
					
					remoteOut.write(buf.array());
				} catch(IOException ioe) {}
			}
		}
		
		//terminate the connection, since our local client disconnected
		terminate();
	}
	
	public void gproxyReconnect() {
		//only reconnect if we're using gproxy and local hasn't disconnected
		if(localSocket != null && localSocket.isConnected() && !terminated && gproxy) {
			host.main.log("[ECConnection] Reconnecting to remote server...");
			
			try {
				remoteSocket = new Socket();
				remoteSocket.setSoTimeout(15000);
				InetSocketAddress remoteSocketAddress = new InetSocketAddress(remoteAddress, remotePort);
				
				String proxy = Config.getString("connection_proxy", null);
				
				if(proxy != null && !proxy.isEmpty()) {
					String[] proxyParts = proxy.split(":");
					
					if(proxyParts.length == 2) {
						host.main.log("[ECConnection] Using proxy [" + proxy + "]");
						InetSocketAddress proxyAddr = new InetSocketAddress(proxyParts[0], Integer.parseInt(proxyParts[1]));
						remoteSocket.connect(proxyAddr, 15000);
						
						//send connect packet
						DataOutputStream tempOut = new DataOutputStream(remoteSocket.getOutputStream());
						tempOut.write(remoteSocketAddress.getAddress().getAddress());
						tempOut.writeShort(remotePort);
					} else {
						host.main.log("[ECConnection] Invalid proxy [" + proxy + "]");
						remoteSocket.connect(remoteSocketAddress, 15000);
					}
				} else {
					host.main.log("[ECConnection] Making direct connection (no proxy set)");
					remoteSocket.connect(remoteSocketAddress, 15000);
				}
				
				synchronized(remoteSync) {
					remoteOut = new DataOutputStream(remoteSocket.getOutputStream());
					isSynchronized = false;
				}
			} catch(IOException ioe) {
				host.main.log("[ECConnection] Connection to remote failed: " + ioe.getLocalizedMessage());
				
				//sleep for a while so we don't spam reconnect
				try {
					Thread.sleep(1000);
				} catch(InterruptedException e) {}
				
				eventRemoteDisconnect(remoteOut);
				return;
			}
			
			new ECForward(this, true, remoteSocket);
			
			sendLocalChat("GProxy++ reconnected to the server!");
			sendLocalChat("==================================================");
			
			//send reconnect packet
			try {
				synchronized(remoteSync) {
					ByteBuffer pbuf = ByteBuffer.allocate(13);
					pbuf.order(ByteOrder.LITTLE_ENDIAN);
					pbuf.put((byte) 248);
					pbuf.put((byte) 2);
					pbuf.putShort((short) 13);
					pbuf.put((byte) pid);
					pbuf.putInt(remoteKey);
					pbuf.putInt(totalRemotePackets);
					
					remoteOut.write(pbuf.array());
				}
			} catch(IOException ioe) {
				ioe.printStackTrace();
				
				//close remote socket and let the GCForward instance we made take care of triggering another reconnect
				try { remoteSocket.close(); } catch(IOException e) {}
			}
		}
	}
	
	public void sendLocalChat(String message) {
		//send message to our local player
		
		if(localSocket != null) {
			ByteBuffer buf;
			byte[] messageBytes = message.getBytes();
			
			//different packets are used depending on if we're in-game
			if(gameStarted) {
				buf = ByteBuffer.allocate(13 + messageBytes.length);
				buf.order(ByteOrder.LITTLE_ENDIAN);
				
				buf.put((byte) 247); //header constant
				buf.put((byte) 15); //chat from host header
				buf.putShort((short) (13 + messageBytes.length)); // packet length, including header
			
				buf.put((byte) 1);
				buf.put((byte) pid);
			
				buf.put((byte) pid);
				buf.put((byte) 32);
			
				buf.put((byte) 0);
				buf.put((byte) 0);
				buf.put((byte) 0);
				buf.put((byte) 0);
				
				buf.put(messageBytes);
				buf.put((byte) 0);
			} else {
				buf = ByteBuffer.allocate(9 + messageBytes.length);
				buf.order(ByteOrder.LITTLE_ENDIAN);
				
				buf.put((byte) 247); //header constant
				buf.put((byte) 15); //chat from host header
				buf.putShort((short) (9 + messageBytes.length)); // packet length, including header
			
				buf.put((byte) 1);
				buf.put((byte) pid);
			
				buf.put((byte) pid);
				buf.put((byte) 16);
			
				buf.put(messageBytes);
				buf.put((byte) 0);
			}
			
			try {
				synchronized(localSync) {
					if(localOut != null) {
						localOut.write(buf.array());
					}
				}
			} catch(IOException ioe) {
				host.main.log("[ECConnection] Local disconnected: " + ioe.getLocalizedMessage());
				eventLocalDisconnect();
			}
		}
	}
	
	public synchronized void terminate() {
		if(!terminated) {
			terminated = true;
			host.main.log("[ECConnection] Terminating connection");
			
			try {
				if(localSocket != null) localSocket.close();
			} catch(IOException e) {}
			
			try {
				if(remoteSocket != null) remoteSocket.close();
			} catch(IOException e) {}
			
			//set everything to null so that we know
			synchronized(localSync) {
				localSocket = null;
				localOut = null;
			}
			
			synchronized(remoteSync) {
				remoteOut = null;
				remoteSocket = null;
			}
		}
	}
	
	public void remoteRec(int header, int identifier, int len, ByteBuffer buf) {
		buf.order(ByteOrder.LITTLE_ENDIAN);
		
		if(header == 247) {
			//synchronize just in case
			//shouldn't be needed because remoteRec will only be called by one thread, and that
			// thread won't be executing if we're reconnecting, but better safe than sorry
			synchronized(remoteSync) {
				totalRemotePackets++;
			}
			
			//acknowledge packet
			if(gproxy) {
				synchronized(remoteSync) {
					try {
						ByteBuffer pbuf = ByteBuffer.allocate(8);
						pbuf.order(ByteOrder.LITTLE_ENDIAN);
						pbuf.put((byte) 248);
						pbuf.put((byte) 3);
						pbuf.putShort((byte) 8);
						pbuf.putInt(totalRemotePackets);
						
						remoteOut.write(pbuf.array());
					} catch(IOException ioe) {
						host.main.log("[ECConnection] Remote disconnected: " + ioe.getLocalizedMessage());
						eventRemoteDisconnect(remoteOut);
					}
					
					if(totalRemotePackets % 50 == 0) {
						host.main.log("[ECConnection] Acknowledged " + totalRemotePackets + " remote packets");
					}
				}
			}
			
			if(identifier == 4) { //SLOTINFOJOIN
				if(len >= 2) {
					int slotInfoSize = buf.get(0) + buf.get(1) * 256;
					
					if(len >= 3 + slotInfoSize) {
						pid = buf.get(2 + slotInfoSize);
						host.main.log("[ECConnection] Found PID=" + pid);
					}
				}
				
				synchronized(remoteSync) {
					if(gproxyEnabled && remoteOut != null) {
						try {
							remoteOut.write((byte) 248);
							remoteOut.write((byte) 1); //GPS_INIT
							remoteOut.write((byte) 8);
							remoteOut.write((byte) 0);
							remoteOut.write((byte) 1); //version
							remoteOut.write((byte) 0);
							remoteOut.write((byte) 0);
							remoteOut.write((byte) 0);
						} catch(IOException ioe) {
							host.main.log("[ECConnection] Remote disconnected: " + ioe.getLocalizedMessage());
							eventRemoteDisconnect(remoteOut);
						}
					}
				}
			} else if(identifier == 11) { //COUNTDOWN_END
				host.main.log("[ECConnection] The game has started.");
				gameStarted = true;
				
				if(host.main.clipGameStart != null && Config.getBoolean("connection_startalert", true)) {
					host.main.clipGameStart.stop();
					host.main.clipGameStart.play();
				}
			} else if(identifier == 12) { //INCOMING_ACTION
				synchronized(remoteSync) {
					if(gproxy) {
						for(int i = numEmptyActionsUsed; i < numEmptyActions; i++) {
							try {
								synchronized(localSync) {
									if(localOut != null) {
										localOut.write(EMPTYACTION);
									}
								}
							} catch(IOException ioe) {
								host.main.log("[ECConnection] Local disconnected: " + ioe.getLocalizedMessage());
								eventLocalDisconnect();
							}
						}
						
						numEmptyActionsUsed = 0;
					}
					
					actionReceived = true;
					lastActionTime = System.currentTimeMillis();
				}
			} else if(identifier == 16) { //START_LAG
				if(gproxy) {
					if(len >= 1) {
						int numLaggers = buf.get(0);
						
						if(len == 1 + numLaggers * 5) {
							for(int i = 0; i < numLaggers; i++) {
								boolean laggerFound = false;
								
								synchronized(laggers) {
									for(Integer x : laggers) {
										if(x == buf.get(1 + i * 5)) laggerFound = true;
									}
									
									if(laggerFound) {
										host.main.log("[ECConnection] warning - received start_lag on known lagger");
									} else {
										laggers.add((int) buf.get(1 + i * 5));
									}
								}
							}
						} else {
							host.main.log("[ECConnection] warning - unhandled start_lag (2)");
						}
					} else {
						host.main.log("[ECConnection] warning - unhandled start_lag (1)");
					}
				}
			} else if(identifier == 17) { //STOP_LAG
				if(gproxy) {
					if(len == 5) {
						boolean laggerFound = false;
						
						synchronized(laggers) {
							for(int i = 0; i < laggers.size(); ) {
								if(laggers.get(i) == buf.get(0)) {
									laggers.remove(i);
									laggerFound = true;
								} else {
									i++;
								}
							}
						}
						
						if(!laggerFound) {
							host.main.log("warning - received stop_lag on unknown lagger");
						}
					} else {
						host.main.log("[ECConnection] warning - unhandled stop_lag");
					}
				}
			} else if(identifier == 72) { //INCOMING_ACTION 2
				if(gproxy) {
					synchronized(remoteSync) {
						for(int i = numEmptyActionsUsed; i < numEmptyActions; i++) {
							try {
								synchronized(localSync) {
									if(localOut != null) {
										localOut.write(EMPTYACTION);
									}
								}
							} catch(IOException ioe) {
								host.main.log("[ECConnection] Local disconnected: " + ioe.getLocalizedMessage());
								eventLocalDisconnect();
							}
						}
						
						numEmptyActionsUsed = numEmptyActions;
					}
				}
			}
			
			//forward data to local
			try {
				synchronized(localSync) {
					if(localOut != null) {
						localOut.write(header);
						localOut.write(identifier);
						byte[] lenBytes = WCUtil.shortToByteArray((short) (len + 4));
						localOut.write(lenBytes[1]);
						localOut.write(lenBytes[0]);
						localOut.write(buf.array(), 0, len);
					}
				}
			} catch(IOException ioe) {
				host.main.log("[ECConnection] Local disconnected: " + ioe.getLocalizedMessage());
				eventLocalDisconnect();
			}
		} else if(header == 248 && gproxyEnabled) { //GPROXY
			if(identifier == 1 && len == 8) { //GPS_INIT
				remotePort = buf.getShort(0);
				remoteKey = buf.getInt(3);
				numEmptyActions = buf.get(7);
				gproxy = true;
				
				//set socket timeout so we disconnect from server
				try {
					remoteSocket.setSoTimeout(15000);
					host.main.log("[ECConnection] Set SO_TIMEOUT=15000ms");
				} catch(IOException ioe) {} //ignore because it's not important
				
				host.main.log("[ECConnection] handshake complete, disconnect protection ready (num=" + numEmptyActions + ")");
				sendLocalChat("Disconnect protection ready, with " + numEmptyActions + " empty actions.");
			} else if(identifier == 2 && len == 4) { //GPS_RECONNECT
				synchronized(localBuffer) {
					host.main.log("[ECConnection] Received GPS_RECONNECT");
					int lastPacket = buf.getInt(0);
					int packetsAlreadyUnqueued = totalLocalPackets - localBuffer.size();
					
					if(lastPacket > packetsAlreadyUnqueued) {
						int packetsToUnqueue = lastPacket - packetsAlreadyUnqueued;
						
						if(packetsToUnqueue > localBuffer.size()) {
							packetsToUnqueue = localBuffer.size();
						}
						
						while(packetsToUnqueue > 0) {
							localBuffer.poll();
							packetsToUnqueue--;
						}
					}
					
					if(remoteOut != null) {
						// send remaining packets from buffer, preserve buffer
						// note: any packets in m_LocalPackets are still sitting at the end of this buffer because they haven't been processed yet
						// therefore we must check for duplicates otherwise we might (will) cause a desync
						Iterator<byte[]> it = localBuffer.iterator();
						
						while(it.hasNext()) {
							try {
								synchronized(remoteSync) {
									if(remoteOut != null) {
										remoteOut.write(it.next());
									}
								}
							} catch(IOException ioe) {
								host.main.log("[ECConnection] Remote disconnected: " + ioe.getLocalizedMessage());
								
								//let GCForward deal with reconnecting again
								synchronized(remoteSync) {
									if(remoteSocket != null) {
										try { remoteSocket.close(); } catch(IOException e) {}
									}
								}
							}
						}
						
						//synchronize again so that we don't check isSynchronized incorrectly
						synchronized(remoteSync) {
							isSynchronized = true;
						}
					}
				}
			} else if(identifier == 3 && len == 4) { //GPS_ACK
				int lastPacket = buf.getInt(0);
				
				synchronized(localBuffer) {
					int packetsAlreadyUnqueued = totalLocalPackets - localBuffer.size();
					host.main.log("[ECConnection] Received GPS_ACK, lastpacket = " + lastPacket + "/" + totalLocalPackets);
					
					if(lastPacket > packetsAlreadyUnqueued) {
						int packetsToUnqueue = lastPacket - packetsAlreadyUnqueued;
						
						if(packetsToUnqueue > localBuffer.size()) {
							packetsToUnqueue = localBuffer.size();
						}
						
						while(packetsToUnqueue > 0) {
							localBuffer.poll();
							packetsToUnqueue--;
						}
					}
				}
			} else if(identifier == 4 && len == 4) { //GPS_REJECT
				int reason = buf.getInt(0);
				host.main.log("[ECConnection] Reconnect rejected: " + reason);
				terminate();
			}
		}
	}
	
	public void localRec(int header, int identifier, int len, ByteBuffer buf) {
		if(header == 247 && identifier == 30) { //REQJOIN
			//we received REQJOIN from client, we can now connect to the game host
			//this is because host counter is unique game host identifier for us
			//we get original host counter before forwarding the packet
			buf.order(ByteOrder.LITTLE_ENDIAN);

			int gameId = buf.getInt(); //client's hostcounter is actually our game identifier
			gameInfo = host.searchGame(gameId); //find the game
			
			if(gameInfo == null) {
				host.main.log("[ECConnection] Invalid game requested (" + gameId + ")");
				terminate();
				return;
			}
			
			buf.getInt(); //ignore entrykey
			byte unknown = buf.get();
			short listenPort = buf.getShort();
			int peerKey = buf.getInt();
			String name = WCUtil.getTerminatedString(buf);

			int remainderLength = len - buf.position();

			//rewrite data for Ghost Client
			byte[] rewrittenUsername = WCUtil.strToBytes(Config.getString("connection_username", name)); //replace LAN name with actual GC name
			int rewrittenLength = 20 + remainderLength + rewrittenUsername.length;
			ByteBuffer lbuf = ByteBuffer.allocate(rewrittenLength);
			lbuf.order(ByteOrder.LITTLE_ENDIAN);

			lbuf.put((byte) header);
			lbuf.put((byte) identifier);
			lbuf.putShort((short) rewrittenLength); //W3GS packet length must include header
			
			//ENT connect uses constant host counter
			lbuf.putInt(15 << 28);

			//replace entry key with sessionkey
			lbuf.putInt(sessionkey);

			lbuf.put(unknown);
			lbuf.putShort(listenPort);
			lbuf.putInt(peerKey);
			lbuf.put(rewrittenUsername);
			lbuf.put((byte) 0); //null terminator
			
			lbuf.put(buf.array(), buf.position(), remainderLength);
			
			host.main.log("[ECConnection] User is requesting " + gameId + " through " + name);
			
			remoteConnected = true;
			remoteAddress = gameInfo.remoteAddress;
			remotePort = gameInfo.remotePort;
			
			try {
				host.main.log("[ECConnection] Found game: " + gameInfo.remoteAddress.getHostAddress() + ":" + gameInfo.remotePort + "; connecting");
				
				String proxy = Config.getString("connection_proxy", null);
				
				if(proxy != null && !proxy.isEmpty()) {
					String[] proxyParts = proxy.split(":");
					
					if(proxyParts.length == 2) {
						host.main.log("[ECConnection] Using proxy [" + proxy + "]");
						remoteSocket = new Socket(proxyParts[0], Integer.parseInt(proxyParts[1]));
						
						//send connect packet
						DataOutputStream tempOut = new DataOutputStream(remoteSocket.getOutputStream());
						tempOut.write(remoteAddress.getAddress());
						tempOut.writeShort(remotePort);
					} else {
						host.main.log("[ECConnection] Invalid proxy [" + proxy + "]");
						remoteSocket = new Socket(remoteAddress, remotePort);
					}
				} else {
					host.main.log("[ECConnection] Making direct connection (no proxy set)");
					remoteSocket = new Socket(remoteAddress, remotePort);
				}
				
				remoteOut = new DataOutputStream(remoteSocket.getOutputStream());
				
				new ECForward(this, true, remoteSocket);
			} catch(IOException ioe) {
				host.main.log("[ECConnection] Connection to remote failed: " + ioe.getLocalizedMessage());
				terminate();
				return;
			}

			totalLocalPackets++;
			
			try {
				remoteOut.write(lbuf.array());
			} catch(IOException ioe) {
				host.main.log("[ECConnection] Remote disconnected at localRec: " + ioe.getLocalizedMessage());
				//simply close the socket so that the GCForward remote instance can handle the error
				try { remoteSocket.close(); } catch(IOException e) {}
			}
		} else if(remoteConnected) {
			//buffer packets if using gproxy
			if(gproxy) {
				//synchronize so that we don't add a packet to buffer while we're reconnecting
				synchronized(localBuffer) {
					byte[] packet = new byte[4 + len];
					packet[0] = (byte) header;
					packet[1] = (byte) identifier;
					byte[] lenBytes = WCUtil.shortToByteArray((short) (len + 4));
					packet[2] = lenBytes[1];
					packet[3] = lenBytes[0];
					System.arraycopy(buf.array(), 0, packet, 4, len);
					
					localBuffer.add(packet);
				}
			}
			
			//increment number of local packets received
			totalLocalPackets++;
			
			if(header == 247 && identifier == 33) { //LEAVEGAME
				leaveGameSent = true;
				host.main.log("[ECConnection] Local left the game");
			}
			
			//send packets to remote server
			//check to make sure we're synchronized with the server
			//(if we're using gproxy, then we might be unsynchronized after we reconnect
			// because we'll be sending the localBuffer packets)
			synchronized(remoteSync) {
				if(isSynchronized && remoteOut != null) {
					try {
						remoteOut.writeByte(header);
						remoteOut.writeByte(identifier);
						byte[] lenBytes = WCUtil.shortToByteArray((short) (len + 4));
						remoteOut.write(lenBytes[1]);
						remoteOut.write(lenBytes[0]);
						remoteOut.write(buf.array(), 0, len);
					} catch(IOException ioe) {
						host.main.log("[ECConnection] Remote disconnected at localRec: " + ioe.getLocalizedMessage());
						//simply close the socket so that the GCForward remote instance can handle the error
						try { remoteSocket.close(); } catch(IOException e) {}
					}
				}
			}
		} else {
			host.main.log("[ECConnection] Bad packet received before REQJOIN: " + identifier + "/" + header);
		}
	}
	
	public void sendEmptyAction() throws IOException {
		host.main.log("[ECConnection] Sending empty action...");
		
		synchronized(laggers) {
			for(int pid : laggers) {
				ByteBuffer stopLag = ByteBuffer.allocate(9);
				stopLag.order(ByteOrder.LITTLE_ENDIAN);
				stopLag.put((byte) 0xF7);
				stopLag.put((byte) 0x11);
				stopLag.put((byte) 0x09);
				stopLag.put((byte) 0x00);
				stopLag.put((byte) pid);
				stopLag.putInt(60000);
				
				synchronized(localSync) {
					if(localOut != null) {
						localOut.write(stopLag.array());
					}
				}
			}
		}
		
		synchronized(localSync) {
			if(localOut != null) {
				localOut.write(EMPTYACTION);
			}
		}
		
		synchronized(laggers) {
			if(!laggers.isEmpty()) {
				int length = 5 + 5 * laggers.size();
				ByteBuffer startLag = ByteBuffer.allocate(length);
				startLag.order(ByteOrder.LITTLE_ENDIAN);
				startLag.put((byte) 0xF7);
				startLag.put((byte) 0x10);
				startLag.putShort((short) length);
				
				for(int pid : laggers) {
					startLag.put((byte) pid);
					startLag.putInt(60000);
				}
				
				synchronized(localSync) {
					if(localOut != null) {
						localOut.write(startLag.array());
					}
				}
			}
		}
	}
	
	class EmptyActionTask extends TimerTask {
		public void run() {
			synchronized(remoteSync) {
				if(remoteOut == null) {
					if(gproxy && actionReceived && System.currentTimeMillis() - lastActionTime > 60000) {
						if(numEmptyActionsUsed < numEmptyActions) {
							//time to send an empty action to reset the lag screen
							try {
								sendEmptyAction();
								numEmptyActionsUsed++;
							} catch(IOException ioe) {
								host.main.log("[ECConnection] Failed to send empty action: " + ioe.getLocalizedMessage());
								ioe.printStackTrace();
							}
							
						} //otherwise wc3 will disconnect soon and nothing we can do
						
						lastActionTime = System.currentTimeMillis();
					}
				}
			}
		}
	}
}

class ECForward extends Thread {
	WCConnection connection;
	boolean isRemote; //whether socket is the remote/host socket (otherwise, it's local/player socket)
	
	Socket socket;
	DataInputStream in;
	
	public ECForward(WCConnection connection, boolean isRemote, Socket socket) {
		this.connection = connection;
		this.isRemote = isRemote;
		
		this.socket = socket;
		try {
			in = new DataInputStream(socket.getInputStream());
		} catch(IOException ioe) {
			connection.host.main.log("[GCForward] Init error: " + ioe.getLocalizedMessage());
		}
		
		start();
	}
	
	public void run() {
		ByteBuffer buf = ByteBuffer.allocate(65536);
		
		while(true) {
			try {
				int header = in.read();
				
				if(header == -1) {
					connection.host.main.log("[GCForward] Socket disconnected");
					connection.terminate();
					break;
				}
				
				int identifier = in.read();
				//read unsigned short in little endian
				int len = (in.read() + in.read() * 256) - 4;
				
				if(len >= 0) {
					in.readFully(buf.array(), 0, len);
					buf.position(0);
					
					if(isRemote) {
						connection.remoteRec(header, identifier, len, buf);
					} else {
						connection.localRec(header, identifier, len, buf);
					}
					
					buf.clear();
				} else {
					connection.host.main.log("[GCForward] Ignoring bad packet, len=" + len);
				}
			} catch(SocketTimeoutException e) {
				connection.host.main.log("[GCForward] Timed out: " + e.getLocalizedMessage());
				
				if(isRemote) {
					connection.eventRemoteDisconnect(connection.remoteOut);
				} else {
					connection.eventLocalDisconnect();
				}
				
				break;
			} catch(IOException ioe) {
				connection.host.main.log("[GCForward] Error: " + ioe.getLocalizedMessage());
				
				if(isRemote) {
					connection.eventRemoteDisconnect(connection.remoteOut);
				} else {
					connection.eventLocalDisconnect();
				}
				
				break;
			}
		}
	}
}