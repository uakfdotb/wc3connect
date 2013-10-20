package net.entgaming.wc3connect;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class GameInfo {
	int uid;
	InetAddress remoteAddress;
	int remotePort;
	int hostCounter;
	
	public GameInfo(int uid, byte[] addr, int port, int hostCounter) {
		this.uid = uid;
		this.remotePort = port;
		this.hostCounter = hostCounter;
		
		try {
			remoteAddress = InetAddress.getByAddress(addr);
		} catch(UnknownHostException uhe) {
			System.out.println("[GameInfo] Error: unknown host on addr bytes: " + uhe.getLocalizedMessage());
			remoteAddress = null;
		}
	}
	
	public GameInfo(int uid, String host, int port, int hostCounter) {
		this.uid = uid;
		this.remotePort = port;
		this.hostCounter = hostCounter;
		
		try {
			remoteAddress = InetAddress.getByName(host);
		} catch(UnknownHostException uhe) {
			System.out.println("[GameInfo] Error: unknown host on hostname: " + uhe.getLocalizedMessage());
			remoteAddress = null;
		}
	}
}
