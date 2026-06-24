package org.openmrs.module.imaging.api.client;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.imaging.OrthancConfiguration;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class OrthancHttpClient {
	
	protected final Log log = LogFactory.getLog(this.getClass());
	
	private String cachedToken = null;
	
	private long tokenExpiry = 0;
	
	/**
	 * Fetch a Keycloak token using client credentials. username = Keycloak client ID, password =
	 * Keycloak client secret The Keycloak URL is derived from the orthancBaseUrl pattern or set via
	 * global property.
	 */
	private String getKeycloakToken(OrthancConfiguration config) throws IOException {
        // Check cached token
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken;
        }

        // Get Keycloak URL from OpenMRS global property or use default
        String keycloakUrl = System.getProperty("imaging.keycloak.url",
            "http://keycloak:8080/realms/ozone/protocol/openid-connect/token");

        String clientId = config.getOrthancUsername();
        String clientSecret = config.getOrthancPassword();

        String formData = "grant_type=client_credentials"
            + "&client_id=" + URLEncoder.encode(clientId, "UTF-8")
            + "&client_secret=" + URLEncoder.encode(clientSecret, "UTF-8");

        URL url = new URL(keycloakUrl);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setDoOutput(true);

        try (DataOutputStream wr = new DataOutputStream(con.getOutputStream())) {
            wr.write(formData.getBytes(StandardCharsets.UTF_8));
        }

        int status = con.getResponseCode();
        if (status != 200) {
            throw new IOException("Failed to get Keycloak token, status: " + status);
        }

        String response = IOUtils.toString(con.getInputStream(), StandardCharsets.UTF_8);
        // Parse access_token from JSON manually
        int start = response.indexOf("\"access_token\":\"") + 16;
        int end = response.indexOf("\"", start);
        cachedToken = response.substring(start, end);

        // Cache for 4 minutes (tokens usually valid 5 minutes)
        tokenExpiry = System.currentTimeMillis() + (4 * 60 * 1000);

        log.info("Successfully obtained Keycloak token for Orthanc access");
        return cachedToken;
    }
	
	public HttpURLConnection createConnection(String method, String url, String path, String username, String password)
	        throws IOException {
		// Create a temporary config to get token
		OrthancConfiguration tempConfig = new OrthancConfiguration();
		tempConfig.setOrthancUsername(username);
		tempConfig.setOrthancPassword(password);
		return createConnectionWithConfig(method, url, path, tempConfig);
	}
	
	public HttpURLConnection createConnectionWithConfig(String method, String baseUrl, String path,
	        OrthancConfiguration config) throws IOException {
		URL serverURL = URI.create(baseUrl).resolve(path).toURL();
		HttpURLConnection con = (HttpURLConnection) serverURL.openConnection();
		con.setRequestMethod(method);
		con.setUseCaches(false);
		try {
			String token = getKeycloakToken(config);
			con.setRequestProperty("token", token);
		}
		catch (Exception e) {
			log.warn("Failed to get Keycloak token, falling back to Basic Auth: " + e.getMessage());
			String auth = config.getOrthancUsername() + ":" + config.getOrthancPassword();
			String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
			con.setRequestProperty("Authorization", "Basic " + encoded);
		}
		return con;
	}
	
	public void sendOrthancQuery(HttpURLConnection con, String query) throws IOException {
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        con.setRequestProperty("charset", "utf-8");
        con.setDoOutput(true);
        byte[] data = query.getBytes(StandardCharsets.UTF_8);
        con.setRequestProperty("Content-Length", Integer.toString(data.length));
        try (DataOutputStream wr = new DataOutputStream(con.getOutputStream())) {
            wr.write(data);
        }
    }
	
	public static void throwConnectionException(OrthancConfiguration config, HttpURLConnection con) throws IOException {
		String errorMessage;
		try {
			InputStream errorStream = con.getErrorStream();
			if (errorStream != null) {
				errorMessage = IOUtils.toString(errorStream, StandardCharsets.UTF_8);
			} else {
				errorMessage = "Unknown error";
			}
		}
		catch (IOException e) {
			errorMessage = "Failed to read error stream: " + e.getMessage();
		}
		throw new IOException("Request to Orthanc server " + config.getOrthancBaseUrl() + " failed with error: "
		        + errorMessage);
	}
	
	public boolean isOrthancReachable(OrthancConfiguration config) {
		try {
			HttpURLConnection connection = createConnectionWithConfig("GET", config.getOrthancBaseUrl(), "/system", config);
			connection.setConnectTimeout(3000);
			connection.setReadTimeout(3000);
			int responseCode = connection.getResponseCode();
			return responseCode == 200;
		}
		catch (IOException e) {
			return false;
		}
	}
	
	public int testOrthancConnection(String url, String username, String password) throws IOException {
		OrthancConfiguration tempConfig = new OrthancConfiguration();
		tempConfig.setOrthancUsername(username);
		tempConfig.setOrthancPassword(password);
		HttpURLConnection con = createConnectionWithConfig("GET", url, "/system", tempConfig);
		int status = con.getResponseCode();
		con.disconnect();
		return status;
	}
	
	public int getStatus(HttpURLConnection con) throws IOException {
		return con.getResponseCode();
	}
	
	public InputStream getResponseStream(HttpURLConnection con) throws IOException {
		return con.getInputStream();
	}
	
	public String getErrorMessage(HttpURLConnection con) throws IOException {
		return con.getResponseMessage();
	}
}
