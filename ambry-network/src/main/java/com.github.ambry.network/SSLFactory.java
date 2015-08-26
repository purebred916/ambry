package com.github.ambry.network;

import com.github.ambry.config.SSLConfig;
import com.github.ambry.utils.Utils;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;


public class SSLFactory {
  public enum Mode {CLIENT, SERVER}
  private String protocol = null;
  private String provider = null;
  private String kmfAlgorithm = null;
  private String tmfAlgorithm = null;
  private SecurityStore keystore = null;
  private String keyPassword = null;
  private SecurityStore truststore = null;
  private String[] cipherSuites = null;
  private String[] enabledProtocols = null;
  private String endpointIdentification = null;
  private SSLContext sslContext = null;
  private boolean needClientAuth = false;
  private boolean wantClientAuth = false;

  public SSLFactory(SSLConfig sslConfig)
      throws Exception {
    this.protocol = sslConfig.sslContextProtocol;
    if (sslConfig.sslContextProvider.length() > 0) {
      this.provider = sslConfig.sslContextProvider;
    }

    ArrayList<String> cipherSuitesList = Utils.splitString(sslConfig.sslCipherSuites, ",");
    if (cipherSuitesList != null && cipherSuitesList.size() > 0) {
      this.cipherSuites = cipherSuitesList.toArray(new String[cipherSuitesList.size()]);
    }

    ArrayList<String> protocolsList = Utils.splitString(sslConfig.sslEnabledProtocol, ",");
    if (protocolsList != null && protocolsList.size() > 0) {
      this.enabledProtocols = protocolsList.toArray(new String[protocolsList.size()]);
    }

    if (sslConfig.sslEndpointIdentificationAlgorithm.length() > 0) {
      this.endpointIdentification = sslConfig.sslEndpointIdentificationAlgorithm;
    }

    if (sslConfig.sslClientAuthentication.equals("required")) {
      this.needClientAuth = true;
    } else if (sslConfig.sslClientAuthentication.equals("requested")) {
      this.wantClientAuth = true;
    }

    if (sslConfig.sslKeymanagerAlgorithm.length() > 0) {
      this.kmfAlgorithm = sslConfig.sslKeymanagerAlgorithm;
    }

    if (sslConfig.sslTrustmanagerAlgorithm.length() > 0) {
      this.tmfAlgorithm = sslConfig.sslTrustmanagerAlgorithm;
    }

    createKeyStore(sslConfig.sslKeyStoreType, sslConfig.sslKeyStorePath, sslConfig.sslKeyStorePassword,
        sslConfig.sslKeyPassword);
    createTrustStore(sslConfig.sslTrustStoreType, sslConfig.sslTrustStorePath, sslConfig.sslTrustStorePassword);

    this.sslContext = createSSLContext();
  }

  private SSLContext createSSLContext()
      throws GeneralSecurityException, IOException {
    SSLContext sslContext;
    if (provider != null) {
      sslContext = SSLContext.getInstance(protocol, provider);
    } else {
      sslContext = SSLContext.getInstance(protocol);
    }

    KeyManager[] keyManagers = null;
    if (keystore != null) {
      String kmfAlgorithm = this.kmfAlgorithm != null ? this.kmfAlgorithm : KeyManagerFactory.getDefaultAlgorithm();
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(kmfAlgorithm);
      KeyStore ks = keystore.load();
      String keyPassword = this.keyPassword != null ? this.keyPassword : keystore.password;
      kmf.init(ks, keyPassword.toCharArray());
      keyManagers = kmf.getKeyManagers();
    }

    String tmfAlgorithm = this.tmfAlgorithm != null ? this.tmfAlgorithm : TrustManagerFactory.getDefaultAlgorithm();
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(tmfAlgorithm);
    KeyStore ts = truststore == null ? null : truststore.load();
    tmf.init(ts);

    sslContext.init(keyManagers, tmf.getTrustManagers(), null);
    return sslContext;
  }

  public SSLEngine createSSLEngine(String peerHost, int peerPort, Mode mode) {
    SSLEngine sslEngine = sslContext.createSSLEngine(peerHost, peerPort);
    if (cipherSuites != null) {
      sslEngine.setEnabledCipherSuites(cipherSuites);
    }
    if (enabledProtocols != null) {
      sslEngine.setEnabledProtocols(enabledProtocols);
    }

    if (mode == Mode.SERVER) {
      sslEngine.setUseClientMode(false);
      if (needClientAuth) {
        sslEngine.setNeedClientAuth(needClientAuth);
      } else {
        sslEngine.setWantClientAuth(wantClientAuth);
      }
    } else {
      sslEngine.setUseClientMode(true);
      SSLParameters sslParams = sslEngine.getSSLParameters();
      sslParams.setEndpointIdentificationAlgorithm(endpointIdentification);
      sslEngine.setSSLParameters(sslParams);
    }
    return sslEngine;
  }

  /**
   * Returns a configured SSLContext.
   * @return SSLContext.
   */
  public SSLContext getSSLContext() {
    return sslContext;
  }

  private void createKeyStore(String type, String path, String password, String keyPassword)
      throws Exception {
    if (path == null && password != null) {
      throw new Exception("SSL key store password is not specified.");
    } else if (path != null && password == null) {
      throw new Exception("SSL key store is not specified, but key store password is specified.");
    } else if (path != null && password != null) {
      this.keystore = new SecurityStore(type, path, password);
      this.keyPassword = keyPassword;
    }
  }

  private void createTrustStore(String type, String path, String password)
      throws Exception {
    if (path == null && password != null) {
      throw new Exception("SSL key store password is not specified.");
    } else if (path != null && password == null) {
      throw new Exception("SSL key store is not specified, but key store password is specified.");
    } else if (path != null && password != null) {
      this.truststore = new SecurityStore(type, path, password);
    }
  }

  private class SecurityStore {
    private final String type;
    private final String path;
    private final String password;

    private SecurityStore(String type, String path, String password) {
      this.type = type == null ? KeyStore.getDefaultType() : type;
      this.path = path;
      this.password = password;
    }

    private KeyStore load()
        throws GeneralSecurityException, IOException {
      FileInputStream in = null;
      try {
        KeyStore ks = KeyStore.getInstance(type);
        in = new FileInputStream(path);
        ks.load(in, password.toCharArray());
        return ks;
      } finally {
        if (in != null) {
          in.close();
        }
      }
    }
  }
}
