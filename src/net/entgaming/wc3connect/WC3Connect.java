package net.entgaming.wc3connect;

import java.applet.AudioClip;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.JApplet;
import javax.swing.JPanel;

public class WC3Connect extends JApplet implements MouseListener {
	Color backgroundColor;
	AudioClip clipGameStart;
	
	WCHost host;
	WCList list;
	
	ColorPanel colorPanel;
	
	List<String> bufferedLogMessages;
	
	public void init() {
		Config.init(this);
		bufferedLogMessages = new ArrayList<String>();
		
		colorPanel = new ColorPanel(this);
		add(colorPanel);
		
		setColor(Color.yellow);
		addMouseListener(this);
	}
	
	public void trust() {
		// Create a trust manager that does not validate certificate chains
		TrustManager[] trustAllCerts = new TrustManager[] {new X509TrustManager() {
				public java.security.cert.X509Certificate[] getAcceptedIssuers() {
					return null;
				}
				public void checkClientTrusted(X509Certificate[] certs, String authType) {
				}
				public void checkServerTrusted(X509Certificate[] certs, String authType) {
				}
			}
		};

		// Install the all-trusting trust manager
		try {
			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
	
			// Create all-trusting host name verifier
			HostnameVerifier allHostsValid = new HostnameVerifier() {
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			};
	
			// Install the all-trusting host verifier
			HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
		} catch(Exception e) {
			e.printStackTrace();
			log("[ECList] Failed to override SSL context: " + e.getLocalizedMessage());
		}
		
		log("[ECList] Trusted all SSL certificates");
	}
	
	public void start() {
		log("WC3Connect starting up");
		
		trust(); //trust all certificates
		
		//preload the game started audio clip
		if(Config.getString("sound_gamestarted", null) != null) {
			clipGameStart = this.getAudioClip(getCodeBase(), Config.getString("sound_gamestarted", null));
		}
		
		host = new WCHost(this);
		host.init();
		
		list = new WCList(this, host);
		new Thread(list).start();
	}
	
	public void setColor(Color color) {
		if(color != backgroundColor) {
			backgroundColor = color;
			colorPanel.setBackground(backgroundColor);
			repaint();
		}
	}
	
	public void stop() {
        host.deinit();
        list.deinit();
    }
	
	public void log(String str) {
		System.out.println(str);
		
		synchronized(bufferedLogMessages) {
			bufferedLogMessages.add(str);
		}
	}
	
	public void mousePressed(MouseEvent e) {
		if(!bufferedLogMessages.isEmpty()) {
			log("[WC3Connect] Attempting to write buffered log messages");
			
			synchronized(bufferedLogMessages) {
				if(!bufferedLogMessages.isEmpty()) {
					try {
						URL target = new URL("https://entgaming.net/wc3connect/log.php");
						HttpURLConnection conn = (HttpURLConnection) target.openConnection();
						conn.setDoOutput(true);
						conn.setRequestMethod("POST");
						OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
						
						for(String str : bufferedLogMessages) {
							writer.write(str + "\n");
						}
						
						writer.flush();
						writer.close();
						conn.getInputStream().close();
						
						log("[WC3Connect] Wrote messages successfully!");
						setColor(Color.MAGENTA);
						bufferedLogMessages.clear();
					} catch(IOException ioe) {
						log("[WC3Connect] Error while logging: " + ioe.getLocalizedMessage());
						ioe.printStackTrace();
					}
				}
			}
		}
	}
	
	public static void report(String problems) {
		String logTarget = Config.getString("log_target", null);
		
		if(logTarget != null) {
			try {
				URL target = new URL(logTarget);
				HttpURLConnection conn = (HttpURLConnection) target.openConnection();
				conn.setDoOutput(true);
				conn.setRequestMethod("POST");
				OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
				writer.write("username=" + Config.getString("connection_username", "unknown") + "\n");
				writer.write(problems);
				writer.flush();
				writer.close();
				conn.getInputStream().close();
			} catch(IOException ioe) {
			}
		}
	}

	public void mouseEntered(MouseEvent e) {}
	public void mouseExited(MouseEvent e) {}
	public void mouseClicked(MouseEvent e) {}
	public void mouseReleased(MouseEvent e) {}
}

class ColorPanel extends JPanel {
	WC3Connect main;
	
	public ColorPanel(WC3Connect main) {
		this.main = main;
		setPreferredSize(main.getSize());
	}
}