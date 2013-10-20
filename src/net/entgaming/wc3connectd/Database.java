package net.entgaming.wc3connectd;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import net.entgaming.wc3connect.WCUtil;

public class Database {
	ArrayList<Connection> connections;
	String host;
	String username;
	String password;
	
	Map<String, Game> games;

	public Database() {
		host = Config.getString("db_host", null);
		username = Config.getString("db_username", null);
		password = Config.getString("db_password", null);
		
		connections = new ArrayList<Connection>();
		games = new HashMap<String, Game>();
	}

	public void init() {
		connections = new ArrayList<Connection>();
		
		try {
			Class.forName("com.mysql.jdbc.Driver");
		} catch(ClassNotFoundException cnfe) {
			System.out.println("[Database] MySQL driver cannot be found: " + cnfe.getLocalizedMessage());
			cnfe.printStackTrace();
		}
	}
	
	//gets a connection
	public Connection connection() {
		synchronized(connections) {
			if(connections.isEmpty()) {
				try {
					System.out.println("[Database] Creating new connection...");
					connections.add(DriverManager.getConnection(host, username, password));
				}
				catch(SQLException e) {
					System.out.println("[Database] Unable to connect to mysql database: " + e.getLocalizedMessage());
					e.printStackTrace();
				}
			}

			//might still have no connections if creating a connection failed
			if(connections.isEmpty()) return null;
			else {
				Connection connection = connections.remove(0);
				try {
					if(connection.isClosed()) {
						return connection();
					} else {
						return connection;
					}
				} catch(SQLException e) {
					return connection();
				}
			}
		}
	}
	
	public void connectionReady(Connection connection) {
		synchronized(connections) {
			connections.add(connection);
		}
	}

	public void update(String ip, int port, byte[] data) {
		String ipport = ip + ":" +  port;
		
		if(games.containsKey(ipport)) {
			Game game = games.get(ipport);
			game.update(); //update time
			
			if(data.length == game.packet.length) {
				//check if data is same
				boolean same = true;
				
				for(int i = 0; i < data.length; i++) {
					if(data[i] != game.packet[i]) {
						same = false;
						break;
					}
				}
				
				if(same) {
					//don't update if same
					return;
				}
			}
		} else {
			games.put(ipport, new Game(data));
		}
		
		String dataStr = WCUtil.hexEncode(data);
		
		try {
			Connection connection = connection();
			if(connection == null) return;
			
			PreparedStatement statement = connection.prepareStatement("SELECT id FROM wc3connect_list WHERE ipport = ?");
			statement.setString(1, ipport);
			
			ResultSet result = statement.executeQuery();
			
			if(result.next()) {
				int id = result.getInt(1);
				statement.close();
				statement = connection.prepareStatement("UPDATE wc3connect_list SET data = ?, time = NOW() WHERE id = ?");
				statement.setString(1, dataStr);
				statement.setInt(2, id);
				statement.execute();
			} else {
				statement.close();
				statement = connection.prepareStatement("INSERT INTO wc3connect_list (ipport, data, time) VALUES (?, ?, NOW())");
				statement.setString(1, ipport);
				statement.setString(2, dataStr);
				statement.execute();
			}
			
			statement.close();
			statement = connection.prepareStatement("DELETE FROM wc3connect_list WHERE TIMESTAMPDIFF(SECOND, time, NOW()) > 15");
			statement.execute();
			statement.close();
			
			connectionReady(connection);
		} catch(SQLException e) {
			System.out.println("[Database] Error: " + e.getLocalizedMessage());
		}
	}
}

class Game {
	byte[] packet;
	long lastUpdate;
	
	public Game(byte[] packet) {
		this.packet = packet;
		update();
	}
	
	public void update() {
		lastUpdate = System.currentTimeMillis();
	}
	
	public boolean isOld() {
		return (System.currentTimeMillis() - lastUpdate) > 15000;
	}
}