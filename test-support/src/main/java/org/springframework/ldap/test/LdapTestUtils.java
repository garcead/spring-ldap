/*
 * Copyright 2005-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ldap.test;

import org.apache.commons.io.IOUtils;
import org.apache.directory.server.core.DefaultDirectoryService;
import org.apache.directory.server.protocol.shared.store.LdifFileLoader;
import org.springframework.core.io.Resource;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.DistinguishedName;
import org.springframework.ldap.core.LdapAttributes;
import org.springframework.ldap.core.support.DefaultDirObjectFactory;
import org.springframework.ldap.ldif.parser.LdifParser;

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.ContextNotEmptyException;
import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.Properties;
import java.util.Set;

/**
 * Utilities for starting, stopping and populating an in-process Apache
 * Directory Server to use for integration testing purposes.
 *
 * @author Mattias Hellborg Arthursson
 */
public class LdapTestUtils {

    public static final String DEFAULT_PRINCIPAL = "uid=admin,ou=system";
    public static final String DEFAULT_PASSWORD = "secret";

    private static EmbeddedLdapServer embeddedServer;

    /**
     * Not to be instantiated.
     */
    private LdapTestUtils() {
    }


    /**
     * Start an in-process Apache Directory Server.
     *
     * @param port                   the port on which the server will be listening.
     * @param defaultPartitionSuffix The default base suffix that will be used
     *                               for the LDAP server.
     * @param defaultPartitionName   The name to use in the directory server
     *                               configuration for the default base suffix.
     * @param principal              The principal to use when starting the directory server.
     * @param credentials            The credentials to use when starting the directory
     *                               server.
     * @param extraSchemas           Set of extra schemas to add to the bootstrap schemas
     *                               of ApacheDS. May be <code>null</code>.
     * @return An unusable DirContext instance.
     * @throws NamingException If anything goes wrong when starting the server.
     * @deprecated use {@link #startEmbeddedServer(int, String, String)} instead.
     */
    public static DirContext startApacheDirectoryServer(int port, String defaultPartitionSuffix,
                                                        String defaultPartitionName, String principal, String credentials, Set extraSchemas) throws NamingException {

        startEmbeddedServer(port, defaultPartitionSuffix, defaultPartitionName);
        return new DummyDirContext();
    }

    /**
     * Start an embedded Apache Directory Server.
     *
     * @param port                   the port on which the server will be listening.
     * @param defaultPartitionSuffix The default base suffix that will be used
     *                               for the LDAP server.
     * @param defaultPartitionName   The name to use in the directory server
     *                               configuration for the default base suffix.
     *
     * @since 1.3.2
     */
    public static void startEmbeddedServer(int port, String defaultPartitionSuffix, String defaultPartitionName) {
        if(embeddedServer != null) {
            throw new IllegalStateException("An embedded server is already started");
        }

        try {
            embeddedServer = EmbeddedLdapServer.newEmbeddedServer(defaultPartitionName, defaultPartitionSuffix, port);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start embedded server");
        }
    }

    public static DirContext startApacheDirectoryServer(int port, String defaultPartitionSuffix,
                                                        String defaultPartitionName, String principal, String credentials) throws NamingException {
        return LdapTestUtils.startApacheDirectoryServer(port, defaultPartitionSuffix, defaultPartitionName, principal,
                credentials, null);
    }

    /**
     * Shuts down the embedded server, if there is one. If no server was previously started in this JVM
     * this is silently ignored.
     *
     * @throws Exception
     * @since 1.3.2
     */
    public static void shutdownEmbeddedServer() throws Exception {
        if(embeddedServer != null) {
            embeddedServer.shutdown();
            embeddedServer = null;
        }
    }

    /**
     * Shut down the in-process Apache Directory Server.
     *
     * @param principal   the principal to be used for authentication.
     * @param credentials the credentials to be used for authentication.
     * @throws Exception If anything goes wrong when shutting down the server.
     * @deprecated use {@link #shutdownEmbeddedServer()} instead.
     */
    public static void destroyApacheDirectoryServer(String principal, String credentials) throws Exception {
        shutdownEmbeddedServer();
    }

    /**
     * Clear the directory sub-tree starting with the node represented by the
     * supplied distinguished name.
     *
     * @param contextSource the ContextSource to use for getting a DirContext.
     * @param name          the distinguished name of the root node.
     * @throws NamingException if anything goes wrong removing the sub-tree.
     */
    public static void clearSubContexts(ContextSource contextSource, Name name) throws NamingException {
        DirContext ctx = null;
        try {
            ctx = contextSource.getReadWriteContext();
            clearSubContexts(ctx, name);
        } finally {
            try {
                ctx.close();
            } catch (Exception e) {
                // Never mind this
            }
        }
    }

    /**
     * Clear the directory sub-tree starting with the node represented by the
     * supplied distinguished name.
     *
     * @param ctx  The DirContext to use for cleaning the tree.
     * @param name the distinguished name of the root node.
     * @throws NamingException if anything goes wrong removing the sub-tree.
     */
    public static void clearSubContexts(DirContext ctx, Name name) throws NamingException {

        NamingEnumeration enumeration = null;
        try {
            enumeration = ctx.listBindings(name);
            while (enumeration.hasMore()) {
                Binding element = (Binding) enumeration.next();
                DistinguishedName childName = new DistinguishedName(element.getName());
                childName.prepend((DistinguishedName) name);

                try {
                    ctx.destroySubcontext(childName);
                } catch (ContextNotEmptyException e) {
                    clearSubContexts(ctx, childName);
                    ctx.destroySubcontext(childName);
                }
            }
        } catch (NamingException e) {
            e.printStackTrace();
        } finally {
            try {
                enumeration.close();
            } catch (Exception e) {
                // Never mind this
            }
        }
    }

    /**
     * Load an Ldif file into an LDAP server.
     *
     * @param contextSource ContextSource to use for getting a DirContext to
     *                      interact with the LDAP server.
     * @param ldifFile      a Resource representing a valid LDIF file.
     * @throws IOException if the Resource cannot be read.
     */
    public static void loadLdif(ContextSource contextSource, Resource ldifFile) throws IOException {
        DirContext context = contextSource.getReadWriteContext();
        try {
            loadLdif(context, ldifFile);
        } finally {
            try {
                context.close();
            } catch (Exception e) {
                // This is not the exception we are interested in.
            }
        }
    }

    public static void cleanAndSetup(ContextSource contextSource, DistinguishedName rootNode, Resource ldifFile)
            throws NamingException, IOException {

        clearSubContexts(contextSource, rootNode);
        loadLdif(contextSource, ldifFile);
    }

    private static void loadLdif(DirContext context, Resource ldifFile) throws IOException {
        try {
            DistinguishedName baseDn = (DistinguishedName)
                    context.getEnvironment().get(DefaultDirObjectFactory.JNDI_ENV_BASE_PATH_KEY);

            LdifParser parser = new LdifParser(ldifFile);
            parser.open();
            while (parser.hasMoreRecords()) {
                LdapAttributes record = parser.getRecord();

                DistinguishedName dn = record.getDN();
                if(baseDn != null) {
                    dn.removeFirst(baseDn);
                }
                context.bind(dn, null, record);
            }
        } catch (NamingException e) {
            throw new RuntimeException("Failed to populate LDIF", e);
        }


//
//        try {
//            DefaultDirectoryService directoryService =
//                    (DefaultDirectoryService) context.getEnvironment().get(DIRECTORY_SERVICE_KEY);
//            if(directoryService == null) {
//                throw new IllegalStateException("The specified context does not appear to have been created by LdapTestUtils");
//            }
//            loadLdif(directoryService, ldifFile);
//        } catch (NamingException e) {
//            throw new RuntimeException("Failed to get environment", e);
//        }
    }

    public static void loadLdif(DefaultDirectoryService directoryService, Resource ldifFile) throws IOException {
        File tempFile = File.createTempFile("spring_ldap_test", ".ldif");
        try {
            InputStream inputStream = ldifFile.getInputStream();
            IOUtils.copy(inputStream, new FileOutputStream(tempFile));
            LdifFileLoader fileLoader = new LdifFileLoader(directoryService.getSession(), tempFile.getAbsolutePath());
            fileLoader.execute();
        } finally {
            try {
                tempFile.delete();
            } catch (Exception e) {
                // Ignore this
            }
        }
    }


    private static Hashtable createEnv(String principal, String credentials) {
        Hashtable env = new Properties();

        env.put(Context.PROVIDER_URL, "");
        env.put(Context.INITIAL_CONTEXT_FACTORY, "org.apache.directory.server.jndi.ServerContextFactory");

        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, credentials);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");

        return env;
    }

    private static Attributes getRootPartitionAttributes(String defaultPartitionName) {
        BasicAttributes attributes = new BasicAttributes();
        BasicAttribute objectClassAttribute = new BasicAttribute("objectClass");
        objectClassAttribute.add("top");
        objectClassAttribute.add("domain");
        objectClassAttribute.add("extensibleObject");
        attributes.put(objectClassAttribute);
        attributes.put("dc", defaultPartitionName);

        return attributes;
    }

}
