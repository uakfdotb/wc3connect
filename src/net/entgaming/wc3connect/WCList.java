package net.entgaming.wc3connect;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

public class WCList implements Runnable {
	WC3Connect main;
	WCHost host;
	URL listServer;
	ByteBuffer buf;
	String filter;
	List<Integer> preferred; //comma-separated list of preferred bots
	
	boolean terminate = false;
	
	public WCList(WC3Connect main, WCHost host) {
		this.main = main;
		this.host = host;
		String listServerStr = Config.getString("list_server", "localhost/list.php");
		filter = Config.getString("list_filter", "");
		
		preferred = new ArrayList<Integer>();
		String[] preferredArray = Config.getString("list_preferred", "").split(",");
		
		for(String str : preferredArray) {
			if(!str.trim().isEmpty()) {
				try {
					preferred.add(Integer.parseInt(str.trim()));
				} catch(NumberFormatException nfe) {} //ignore
			}
		}
		
		if(filter == null) {
			filter = "";
		} else {
			int index = filter.indexOf("#");
			
			if(index > 0) {
				filter = filter.substring(0, index);
			}
		}
		
		try {
			listServer = new URL(listServerStr);
		} catch(MalformedURLException e) {
			main.log("[WCList] Malformed target URL: " + listServerStr);
			e.printStackTrace();
		}
		
		buf = ByteBuffer.allocate(65536);
	}
	
	public void deinit() {
		terminate = true;
		
		synchronized(this) {
			this.notifyAll();
		}
	}

	public void run() {
		while(!terminate) {
			HttpsURLConnection conn;
			String line;
			
			main.log("[WCList] Connecting to server...");
			
			try {
				conn = (HttpsURLConnection) listServer.openConnection();
				conn.setRequestMethod("GET");
				BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				
				host.clearGames();
				
				try {
					Thread.sleep(200);
				} catch(InterruptedException ie) {}
				
				//get each line and store
				ArrayList<String> lines = new ArrayList<String>();
				
				while ((line = in.readLine()) != null) {
					lines.add(line);
				}
				
				in.close();
				
				try {
					Thread.sleep(200);
				} catch(InterruptedException ie) {}
				
				//use filter on first pass if desired
				if(filter != null && !filter.isEmpty()) {
					Iterator<String> it = lines.iterator();
					
					while(it.hasNext()) {
						byte[] data = WCUtil.hexDecode(it.next());
						buf.clear();
						buf.put(data);
						buf.position(0);
						
						if(host.receivedUDP(buf, filter, null)) {
							it.remove();
						}
					}
					
					main.log("[WCList] Listing with filter=" + filter + " complete");
				}
				
				//use preferred bots for second pass
				if(preferred != null && !preferred.isEmpty()) {
					Iterator<String> it = lines.iterator();
					
					while(it.hasNext()) {
						byte[] data = WCUtil.hexDecode(it.next());
						buf.clear();
						buf.put(data);
						buf.position(0);
						
						if(host.receivedUDP(buf, null, preferred)) {
							it.remove();
						}
					}
					
					main.log("[WCList] Listing with " + preferred.size() + " preferred bots complete");
				}
				
				//randomize lines for remaining games so user sees different games each time
				Collections.shuffle(lines);
				
				for(String x : lines) {
					byte[] data = WCUtil.hexDecode(x);
					buf.clear();
					buf.put(data);
					buf.position(0);
					
					host.receivedUDP(buf, null, null);
				}
				
				//flush the packets that we've created with above calls
				host.flush();
				
				main.log("[WCList] Listing complete");
			} catch (Exception e) {
				main.log("[WCList] Error during listing: " + e.getLocalizedMessage());
				e.printStackTrace();
				main.setColor(Color.CYAN);
			}
			
			if(terminate) break;
			
			synchronized(this) {
				try {
					this.wait(10000);
				} catch(InterruptedException e) {}
			}
		}
	}
}
