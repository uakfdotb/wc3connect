package net.entgaming.wc3connect;


public class Config {
	private static WC3Connect APPLET;
	
	public static void init(WC3Connect applet) {
		Config.APPLET = applet;
	}
	
	public static String getString(String key, String defaultValue) {
		String str = APPLET.getParameter(key);
		
		if(str == null || str.trim().equals("")) {
			return defaultValue;
		} else {
			return str;
		}
	}
	
	public static int getInt(String key, int defaultValue) {
		try {
			String result = APPLET.getParameter(key);
			
			if(result != null) {
				return Integer.parseInt(result);
			} else {
				return defaultValue;
			}
		} catch(NumberFormatException nfe) {
			APPLET.log("[Config] Warning: invalid integer for key " + key);
			return defaultValue;
		}
	}
	
	public static long getLong(String key, int defaultValue) {
		try {
			String result = APPLET.getParameter(key);
			
			if(result != null) {
				return Long.parseLong(result);
			} else {
				return defaultValue;
			}
		} catch(NumberFormatException nfe) {
			APPLET.log("[Config] Warning: invalid long for key " + key);
			return defaultValue;
		}
	}
	
	public static boolean getBoolean(String key, boolean defaultValue) {
		String result = APPLET.getParameter(key);
		
		if(result != null) {
			if(result.equals("true") || result.equals("1")) return true;
			else if(result.equals("false") || result.equals("0")) return false;
			else {
				APPLET.log("[Config] Warning: invalid boolean for key " + key);
				return defaultValue;
			}
		} else {
			return defaultValue;
		}
	}
	
	public static boolean containsKey(String key) {
		return APPLET.getParameter(key) != null;
	}
}