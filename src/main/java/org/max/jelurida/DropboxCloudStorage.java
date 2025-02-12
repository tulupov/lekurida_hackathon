package org.max.jelurida;

import nxt.addons.JO;
import nxt.addons.RequestContext;
import nxt.util.Logger;
import org.max.jelurida.CloudStorageContract.Params;

import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.util.UUID;

/**
 * @author maxtulupov@gmail.com
 */
public class DropboxCloudStorage {

    private Params params;

    public DropboxCloudStorage(Params params) {
        this.params = params;
    }

    public JO upload(RequestContext requestContext) throws ApplicationException {
        return uploadFile(requestContext);
    }

    public String getLink(JO transaction) throws ApplicationException {
        String path = transaction.getString("path_display");
        if (path == null) {
            throw new ApplicationException("File not exists", 30009);
        }
        JO request = new JO();
        request.put("path", path);
        HttpURLConnection connection = createConnection(params.dropboxApiUrl() + "/get_temporary_link ", "application/json", request, false);
        JO result = finishConnection(connection);
        if (result.containsKey("error_summary")) {
            throw new ApplicationException("Error downloading file " + result.getString("error_summary"), 30008);
        }
        validateFile(transaction, result);
        return result.getString("link");
    }

    private void validateFile(JO originalData, JO serverData) throws ApplicationException {
        if (!serverData.getJo("metadata").getString("content_hash").equals(originalData.getString("content_hash"))) {
            throw new ApplicationException("File was modified after upload", 30009);
        }
    }

    private JO uploadFile(RequestContext requestContext) throws ApplicationException {
        byte[] buf = new byte[1024 * 1024];
        int bytesWritten;
        long counter = 0;
        long totalBytesWritten = 0;
        Part part;
        try {
            part = requestContext.getRequest().getPart("file");
        } catch (Exception e) {
            throw new ApplicationException("Can't get file part from multipart", e, 30000);
        }
        String contentType = part.getContentType() == null ? "application/octet-stream" : part.getContentType();
        String fileName = part.getSubmittedFileName();
        if (fileName == null) {
            fileName = UUID.randomUUID().toString() + ".unk";
            Logger.logInfoMessage("client doesn't provide fileName. Autogenerated file name " + fileName);
        }
        JO meta = new JO();
        meta.put("close", false);
        HttpURLConnection connection = null;
        String sessionId = null;
        try {
            connection = createConnection(params.dropboxContentUrl() + "/upload_session/start", contentType, meta, true);
            final InputStream inputStream = part.getInputStream();
            while (true) {
                bytesWritten = inputStream.read(buf);
                if (bytesWritten < 0) {
                    break;
                }
                connection.getOutputStream().write(buf, 0, bytesWritten);
                totalBytesWritten += bytesWritten;
                counter++;
                if (counter % 10 == 0) {
                    JO result = finishConnection(connection);
                    sessionId = result.getString("session_id", sessionId);
                    JO uploadRequestData = createUploadRequestData(sessionId, totalBytesWritten, true);
                    connection = createConnection(params.dropboxContentUrl() + "/upload_session/append_v2", contentType, uploadRequestData, true);
                }
            }
        } catch (Exception e) {
            Logger.logErrorMessage("error", e);
        } finally {
            JO jo = finishConnection(connection);
            sessionId = jo.getString("session_id", sessionId);
            if (totalBytesWritten != part.getSize()) {
                throw new ApplicationException("Not whole file stored", 30001);
            }
            JO uploadRequestData = createUploadRequestData(sessionId, totalBytesWritten, false);
            JO commit = new JO();
            commit.put("path", "/" + UUID.randomUUID().toString() + "_" + fileName);
            commit.put("mode", "add");
            commit.put("autorename", true);
            commit.put("mute", false);
            commit.put("strict_conflict", false);
            uploadRequestData.put("commit", commit);
            connection = createConnection(params.dropboxContentUrl() + "/upload_session/finish", contentType, uploadRequestData, true);
            JO result = finishConnection(connection);
            result.put("filename", fileName);
            return result;
        }
    }

    private JO createUploadRequestData(String sessionId, long offset, boolean includeClose) {
        JO jo = new JO();
        if (includeClose) {
            jo.put("close", false);
        }
        JO cursor = new JO();
        cursor.put("session_id", sessionId);
        cursor.put("offset", offset);
        jo.put("cursor", cursor);
        return jo;
    }

    private HttpURLConnection createConnection(String url, String contentType, JO meta, boolean streamHandle) throws ApplicationException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
        } catch (IOException e) {
            throw new ApplicationException("Can't establish connection with cloud storage", e, 30005);
        }
        connection.setRequestProperty("Authorization", params.dropboxAccessToken());
        connection.setRequestProperty("Content-Type", contentType);
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setDefaultUseCaches(false);
        try {
            connection.setRequestMethod("POST");
        } catch (ProtocolException e) {
        }
        if (streamHandle) {
            connection.setRequestProperty("Dropbox-API-Arg", meta.toJSONString());
        } else {
            try {
                connection.connect();
                connection.getOutputStream().write(meta.toJSONString().getBytes());
            } catch (IOException e) {
                throw new ApplicationException("Can't write to cloud storage", e, 30004);
            }
        }
        return connection;
    }

    private JO finishConnection(HttpURLConnection connection) throws ApplicationException {
        if (connection == null) {
            return new JO();
        }
        InputStreamReader reader = null;
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                try {
                    reader = new InputStreamReader(connection.getInputStream());
                    return JO.parse(reader);
                } catch (Exception e) {
                    return new JO();
                }

            } else {
                throw new ApplicationException("Error in response = " + responseCode + " message = " + connection.getResponseMessage(), 30006);
            }
        } catch (IOException e) {
            throw new ApplicationException("Can not read response from cloud storage", e, 30007);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e) {
                }
            }
            connection.disconnect();
        }
    }
}
