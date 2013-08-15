package org.vertx.java.platform.impl.resolver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.vertx.java.core.Vertx;
import org.vertx.java.platform.impl.ModuleIdentifier;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

public class TrayRepoResolver implements RepoResolver {
  private static final String HOME_DIR = System.getProperty("user.home");

  private final Vertx vertx;
  private final String repoID;

  public TrayRepoResolver(Vertx vertx, String repoID) {
    this.vertx = vertx;
    this.repoID = repoID;
  }

  @Override
  public boolean getModule(String filename, ModuleIdentifier moduleIdentifier) {
    try {
      String myAccessKeyID = null;
      String mySecretKey = null;
      try {
        List<String> files = Files.readAllLines(new File(HOME_DIR + "/.trayrepo").toPath(), StandardCharsets.UTF_8);
        String line1 = files.get(0);
        String[] creds = line1.split(":");
        myAccessKeyID = creds[0];
        mySecretKey = creds[1];
      } catch (Exception e) {
        try {
          System.err.println("[TRAY REPO] problem loading config file (~/.trayrepo) " + e.getMessage());
          String line1 = System.getenv("TRAY_REPO_CREDS");
          String[] creds = line1.split(":");
          myAccessKeyID = creds[0];
          mySecretKey = creds[1];
        } catch (Exception e2) {
          System.err.println("[TRAY REPO] problem loading environment variable TRAY_REPO_CREDS " + e2.getMessage());
          return false;
        }
      }

      AWSCredentials myCredentials = new BasicAWSCredentials(myAccessKeyID, mySecretKey);

      AmazonS3 s3client = new AmazonS3Client(myCredentials);
      S3Object object = s3client.getObject(new GetObjectRequest(repoID, getMavenURI(moduleIdentifier) + "maven-metadata.xml"));
      String metadata = convertStreamToString(object.getObjectContent());
      String resource = getMavenURI(moduleIdentifier) + getResourceName(metadata, moduleIdentifier, false);
      System.out.println("[TRAY REPO] Downloading " + resource);
      S3Object file = s3client.getObject(new GetObjectRequest(repoID, resource));
      write(file.getObjectContent(), filename);
      return true;
    } catch (AmazonServiceException e) {
      System.err.println("[TRAY REPO] " + e.getMessage());
    } catch (AmazonClientException e) {
      System.err.println("[TRAY REPO] " + e.getMessage());
    } catch (Exception e) {
      System.err.println("[TRAY REPO] failure: " + e.toString());
    }
    return false;
  }

  static void write(InputStream is, String filename) {
    try {
      try {
        OutputStream os = new FileOutputStream(filename);
        try {
          byte[] buffer = new byte[4096];
          for (int n; (n = is.read(buffer)) != -1;)
            os.write(buffer, 0, n);
        } finally {
          os.close();
        }
      } finally {
        is.close();
      }
    } catch (IOException e) {
    }
  }

  static String convertStreamToString(java.io.InputStream is) {
    try {
      java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
      return s.hasNext() ? s.next() : "";
    } finally {
      try {
        is.close();
      } catch (Exception e) {
      }
    }
  }

  static String getResourceName(String mavenMetadata, ModuleIdentifier identifier, boolean modSuffix) {
    int pos = mavenMetadata.indexOf("<snapshot>");
    String actualURI = null;
    if (pos != -1) {
      int pos2 = mavenMetadata.indexOf("<timestamp>", pos);
      if (pos2 != -1) {
        String timestamp = mavenMetadata.substring(pos2 + 11, pos2 + 26);
        int pos3 = mavenMetadata.indexOf("<buildNumber>", pos);
        int pos4 = mavenMetadata.indexOf('<', pos3 + 12);
        String buildNumber = mavenMetadata.substring(pos3 + 13, pos4);
        // Timestamped SNAPSHOT
        actualURI = identifier.getName() + '-'
            + identifier.getVersion().substring(0, identifier.getVersion().length() - 9) + '-' + timestamp + '-'
            + buildNumber + (modSuffix ? "" : "-mod") + ".zip";
      }
    }
    if (actualURI == null) {
      // Non timestamped SNAPSHOT
      actualURI = getNonVersionedResourceName(identifier, modSuffix);
    }
    return actualURI;
  }

  static String getMavenURI(ModuleIdentifier moduleIdentifier) {
    StringBuilder uri = new StringBuilder('/');
    String[] groupParts = moduleIdentifier.getOwner().split("\\.");
    for (String groupPart : groupParts) {
      uri.append(groupPart).append('/');
    }
    uri.append(moduleIdentifier.getName()).append('/').append(moduleIdentifier.getVersion()).append('/');
    return uri.toString();
  }

  private static String getNonVersionedResourceName(ModuleIdentifier identifier, boolean modSuffix) {
    return identifier.getName() + '-' + identifier.getVersion() + (modSuffix ? "-mod" : "") + ".zip";
  }

  @Override
  public boolean isOldStyle() {
    return false;
  }

}
