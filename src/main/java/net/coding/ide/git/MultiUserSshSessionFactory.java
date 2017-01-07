/*
 * Copyright (c) 2014-2016 CODING.
 */

package net.coding.ide.git;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Created by vangie on 14/12/30.
 *
 * @see JschConfigSessionFactory
 */
public class MultiUserSshSessionFactory extends SshSessionFactory {

    private final Map<String, JSch> byIdentityFile = new HashMap<String, JSch>();

    private JSch defaultJSch;

    private OpenSshConfig config;

    @Override
    public synchronized RemoteSession getSession(URIish uri,
                                                 CredentialsProvider credentialsProvider, FS fs, int tms)
            throws TransportException {

        String user = uri.getUser();
        final String pass = uri.getPass();
        String host = uri.getHost();
        int port = uri.getPort();

        try {
            if (config == null)
                config = OpenSshConfig.get(fs);

            final OpenSshConfig.Host hc = config.lookup(host);
            host = hc.getHostName();
            if (port <= 0)
                port = hc.getPort();
            if (user == null)
                user = hc.getUser();

            Session session = createSession(credentialsProvider, fs, user,
                    pass, host, port, hc);

            int retries = 0;
            while (!session.isConnected()) {
                try {
                    retries++;
                    session.connect(tms);
                } catch (JSchException e) {
                    session.disconnect();
                    session = null;
                    // Make sure our known_hosts is not outdated
                    knownHosts(getJSch(credentialsProvider, hc, fs), fs);

                    if (isAuthenticationCanceled(e)) {
                        throw e;
                    } else if (isAuthenticationFailed(e)
                            && credentialsProvider != null) {
                        // if authentication failed maybe credentials changed at
                        // the remote end therefore reset credentials and retry
                        if (retries < 3) {
                            credentialsProvider.reset(uri);
                            session = createSession(credentialsProvider, fs,
                                    user, pass, host, port, hc);
                        } else
                            throw e;
                    } else if (retries >= hc.getConnectionAttempts()) {
                        throw e;
                    } else {
                        try {
                            Thread.sleep(1000);
                            session = createSession(credentialsProvider, fs,
                                    user, pass, host, port, hc);
                        } catch (InterruptedException e1) {
                            throw new TransportException(
                                    JGitText.get().transportSSHRetryInterrupt,
                                    e1);
                        }
                    }
                }
            }

            return new JschSession(session, uri);

        } catch (JSchException je) {
            final Throwable c = je.getCause();
            if (c instanceof UnknownHostException)
                throw new TransportException(uri, JGitText.get().unknownHost);
            if (c instanceof ConnectException)
                throw new TransportException(uri, c.getMessage());
            throw new TransportException(uri, je.getMessage(), je);
        }

    }

    private static boolean isAuthenticationFailed(JSchException e) {
        return e.getCause() == null && e.getMessage().equals("Auth fail"); //$NON-NLS-1$
    }

    private static boolean isAuthenticationCanceled(JSchException e) {
        return e.getCause() == null && e.getMessage().equals("Auth cancel"); //$NON-NLS-1$
    }

    private Session createSession(CredentialsProvider credentialsProvider,
                                  FS fs, String user, final String pass, String host, int port,
                                  final OpenSshConfig.Host hc) throws JSchException {
        final Session session = createSession(credentialsProvider, hc, user, host, port, fs);
        // We retry already in getSession() method. JSch must not retry
        // on its own.
        session.setConfig("MaxAuthTries", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        if (pass != null)
            session.setPassword(pass);
        final String strictHostKeyCheckingPolicy = hc
                .getStrictHostKeyChecking();
        if (strictHostKeyCheckingPolicy != null)
            session.setConfig("StrictHostKeyChecking", //$NON-NLS-1$
                    strictHostKeyCheckingPolicy);
        final String pauth = hc.getPreferredAuthentications();
        if (pauth != null)
            session.setConfig("PreferredAuthentications", pauth); //$NON-NLS-1$
        if (credentialsProvider != null && !(credentialsProvider instanceof PrivateKeyCredentialsProvider)
                && (!hc.isBatchMode() || !credentialsProvider.isInteractive())) {
            session.setUserInfo(new CredentialsProviderUserInfo(session,
                    credentialsProvider));
        }
        configure(hc, session);
        return session;
    }

    /**
     * Create a new remote session for the requested address.
     *
     * @param hc   host configuration
     * @param user login to authenticate as.
     * @param host server name to connect to.
     * @param port port number of the SSH daemon (typically 22).
     * @param fs   the file system abstraction which will be necessary to
     *             perform certain file system operations.
     * @return new session instance, but otherwise unconfigured.
     * @throws JSchException the session could not be created.
     */
    protected Session createSession(final CredentialsProvider credentialsProvider, final OpenSshConfig.Host hc,
                                    final String user, final String host, final int port, FS fs)
            throws JSchException {
        Session session =  getJSch(credentialsProvider, hc, fs).getSession(user, host, port);

        // FIXME workaround for com.jcraft.jsch.JSchException: UnknownHostKey
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        return session;
    }

    /**
     * Provide additional configuration for the session based on the host
     * information. This method could be used to supply {@link com.jcraft.jsch.UserInfo}.
     *
     * @param hc      host configuration
     * @param session session to configure
     */
    protected void configure(OpenSshConfig.Host hc, Session session) {
    }

    /**
     * Obtain the JSch used to create new sessions.
     *
     * @param hc host configuration
     * @param fs the file system abstraction which will be necessary to
     *           perform certain file system operations.
     * @return the JSch instance to use.
     * @throws JSchException the user configuration could not be created.
     */
    protected JSch getJSch(final CredentialsProvider credentialsProvider, final OpenSshConfig.Host hc, FS fs) throws JSchException {
        if (defaultJSch == null) {
            defaultJSch = createDefaultJSch(fs);
            for (Object name : defaultJSch.getIdentityNames())
                byIdentityFile.put((String) name, defaultJSch);
        }

        File identityFile = null;
        File knownHostsFile = null;

        if (credentialsProvider instanceof PrivateKeyCredentialsProvider) {
            PrivateKeyCredentialsProvider privateKeyCredentialsProvider = (PrivateKeyCredentialsProvider) credentialsProvider;
            Identity identity = new Identity();
            KnownHosts knownHosts = new KnownHosts();
            privateKeyCredentialsProvider.get(null, identity, knownHosts);
            identityFile = identity.getValue();
            knownHostsFile = knownHosts.getValue();

        }

        if (identityFile == null) {
            identityFile = hc.getIdentityFile();
            if (identityFile == null)
                return defaultJSch;
        }


        final String identityKey = identityFile.getAbsolutePath();
        JSch jsch = byIdentityFile.get(identityKey);
        if (jsch == null) {
            jsch = new JSch();
            jsch.setHostKeyRepository(defaultJSch.getHostKeyRepository());
            jsch.addIdentity(identityKey);
            if( knownHostsFile != null)
                jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
            byIdentityFile.put(identityKey, jsch);
        }
        return jsch;
    }

    /**
     * @param fs the file system abstraction which will be necessary to
     *           perform certain file system operations.
     * @return the new default JSch implementation.
     * @throws JSchException known host keys cannot be loaded.
     */
    protected JSch createDefaultJSch(FS fs) throws JSchException {
        final JSch jsch = new JSch();
        knownHosts(jsch, fs);
        identities(jsch, fs);
        return jsch;
    }

    private static void knownHosts(final JSch sch, FS fs) throws JSchException {
        final File home = fs.userHome();
        if (home == null)
            return;
        final File known_hosts = new File(new File(home, ".ssh"), "known_hosts"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            final FileInputStream in = new FileInputStream(known_hosts);
            try {
                sch.setKnownHosts(in);
            } finally {
                in.close();
            }
        } catch (FileNotFoundException none) {
            // Oh well. They don't have a known hosts in home.
        } catch (IOException err) {
            // Oh well. They don't have a known hosts in home.
        }
    }

    private static void identities(final JSch sch, FS fs) {
        final File home = fs.userHome();
        if (home == null)
            return;
        final File sshdir = new File(home, ".ssh"); //$NON-NLS-1$
        if (sshdir.isDirectory()) {
            loadIdentity(sch, new File(sshdir, "identity")); //$NON-NLS-1$
            loadIdentity(sch, new File(sshdir, "id_rsa")); //$NON-NLS-1$
            loadIdentity(sch, new File(sshdir, "id_dsa")); //$NON-NLS-1$
        }
    }

    private static void loadIdentity(final JSch sch, final File priv) {
        if (priv.isFile()) {
            try {
                sch.addIdentity(priv.getAbsolutePath());
            } catch (JSchException e) {
                // Instead, pretend the key doesn't exist.
            }
        }
    }

}
