package net.entgaming.wc3connectd;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

public class Config {
	static Properties properties;
	
	public static boolean init(String propertiesFile) {
		properties = new Properties();
		System.out.println("[Config] Loading configuration file " + propertiesFile);
		
		try {
			properties.load(new FileInputStream(propertiesFile));
			return true;
		} catch(FileNotFoundException e) {
			System.out.println("[Config] Fatal error: could not find configuration file " + propertiesFile);
			return false;
		} catch(IOException e) {
			System.out.println("[Config] Fatal error: error while reading from configuration file " + propertiesFile);
			return false;
		}
	}
	
	public static String getString(String key, String defaultValue) {
		String str = properties.getProperty(key, defaultValue);
		
		if(str == null || str.trim().equals("")) {
			return defaultValue;
		} else {
			return str;
		}
	}
	
	public static int getInt(String key, int defaultValue) {
		try {
			String result = properties.getProperty(key, null);
			
			if(result != null) {
				return Integer.parseInt(result);
			} else {
				return defaultValue;
			}
		} catch(NumberFormatException nfe) {
			System.out.println("[Config] Warning: invalid integer for key " + key);
			return defaultValue;
		}
	}
	
	public static boolean getBoolean(String key, boolean defaultValue) {
		String result = properties.getProperty(key, null);
		
		if(result != null) {
			if(result.equals("true") || result.equals("1")) return true;
			else if(result.equals("false") || result.equals("0")) return false;
			else {
				System.out.println("[Config] Warning: invalid boolean for key " + key);
				return defaultValue;
			}
		} else {
			return defaultValue;
		}
	}
	
	public static boolean containsKey(String key) {
		return properties.containsKey(key);
	}
}