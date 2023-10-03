package org.bouncycastle.mls;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.hpke.HPKEContext;
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.mls.codec.MLSOutputStream;
import org.bouncycastle.mls.crypto.CipherSuite;
import org.bouncycastle.mls.crypto.Secret;
import org.bouncycastle.mls.codec.PreSharedKeyID;
import org.bouncycastle.util.Arrays;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

public class KeyScheduleEpoch {
    public static class PSKWithSecret {
        PreSharedKeyID id;
        Secret secret;

        public PSKWithSecret(PreSharedKeyID id, Secret secret) {
            this.id = id;
            this.secret = secret;
        }
    }

    public byte[] receiveExternalInit(byte[] kemOut) throws IOException
    {
        // do export (private key)
        final int L = suite.getKDF().getHashLength();
        final byte[] labelData = "MLS 1.0 external init secret".getBytes(StandardCharsets.UTF_8);
        HPKEContext ctx = suite.getHPKE().setupBaseR(kemOut, externalKeyPair, new byte[0]);

        return ctx.export(labelData, L);
    }

    public static class JoinSecrets {
        // Cached values
        private final CipherSuite suite;
        // Public values
        public final Secret joinerSecret;

        public Secret welcomeSecret;
        public Secret welcomeKey;
        public Secret welcomeNonce;
        private Secret memberSecret; // Held to derive further secrets

        static class PSKLabel implements MLSOutputStream.Writable {
            PreSharedKeyID id;
            short index;
            short count;

            public PSKLabel(PreSharedKeyID id, short index, short count) {
                this.id = id;
                this.index = index;
                this.count = count;
            }

            @Override
            public void writeTo(MLSOutputStream stream) throws IOException {
                stream.write(id);
                stream.write(index);
                stream.write(count);
            }
        }

        /*
                         0                               0    = psk_secret_[0]
                         |                               |
                         V                               V
        psk_[0]   --> Extract --> ExpandWithLabel --> Extract = psk_secret_[1]
                                                         |
                         0                               |
                         |                               |
                         V                               V
        psk_[1]   --> Extract --> ExpandWithLabel --> Extract = psk_secret_[2]
                                                         |
                         0                              ...
                         |                               |
                         V                               V
        psk_[n-1] --> Extract --> ExpandWithLabel --> Extract = psk_secret_[n]
         */
        public static Secret pskSecret(CipherSuite suite, List<PSKWithSecret> psks) throws IOException {
            Secret pskSecret = Secret.zero(suite);
            if (psks == null || psks.isEmpty()) {
                return pskSecret;
            }

            short index = 0;
            short count = (short) psks.size();
            for (PSKWithSecret psk : psks) {
                PSKLabel label = new PSKLabel(psk.id, index, count);
                byte[] pskLabel = MLSOutputStream.encode(label);
                index += 1;

                Secret pskExtracted = Secret.extract(suite, Secret.zero(suite), psk.secret);
                Secret pskInput = pskExtracted.expandWithLabel(suite, "derived psk", pskLabel, suite.getKDF().getHashLength());
                pskSecret = Secret.extract(suite, pskInput, pskSecret);
            }

            return pskSecret;
        }

        /*
                   init_secret_[n-1]
                         |
                         |
                         V
   commit_secret --> KDF.Extract
                         |
                         |
                         V
                 ExpandWithLabel(., "joiner", GroupContext_[n], KDF.Nh)
                         |
                         |
                         V
                    joiner_secret

        */
        public static JoinSecrets forMember(CipherSuite suite, Secret initSecret, Secret commitSecret, List<PSKWithSecret> psks, byte[] context) throws IOException
        {
            Secret preJoinerSecret = Secret.extract(suite, initSecret, commitSecret);
            Secret joinerSecret = preJoinerSecret.expandWithLabel(suite, "joiner", context, suite.getKDF().getHashLength());
            return new JoinSecrets(suite, joinerSecret, psks);
        }
        // todo change to
//        public static JoinSecrets forMember(CipherSuite suite, Secret initSecret, Secret commitSecret, Secret pskSecret, byte[] context) throws IOException {
//            Secret preJoinerSecret = Secret.extract(suite, initSecret, commitSecret);
//            Secret joinerSecret = preJoinerSecret.expandWithLabel(suite,"joiner", context, suite.getKDF().getHashLength());
//            return new JoinSecrets(suite, joinerSecret, pskSecret);
//        }

        /*
                     joiner_secret
                          |
                          |
                          V
psk_secret (or 0) --> KDF.Extract
                          |
                          |
                          +--> DeriveSecret(., "welcome")
                          |    = welcome_secret
                          |
                          V
                  ExpandWithLabel(., "epoch", GroupContext_[n], KDF.Nh)
                          |
                          |
                          V
                     epoch_secret
     */
        public JoinSecrets(CipherSuite suite, Secret joinerSecret, List<PSKWithSecret> psks) throws IOException {
            this.suite = suite;
            this.joinerSecret = joinerSecret;
            this.memberSecret = Secret.extract(suite, joinerSecret, pskSecret(suite, psks));
            // Carry-forward values
            // Held to avoid consuming joinerSecret
            this.welcomeSecret = memberSecret.deriveSecret(suite, "welcome");
            this.welcomeKey = welcomeSecret.expand(suite, "key", suite.getAEAD().getKeySize());
            this.welcomeNonce = welcomeSecret.expand(suite, "nonce", suite.getAEAD().getNonceSize());
        }
        public JoinSecrets(CipherSuite suite, Secret joinerSecret, Secret pskSecret) throws IOException {
            this.suite = suite;
            this.joinerSecret = joinerSecret;
            this.memberSecret = Secret.extract(suite, joinerSecret, pskSecret);
            // Carry-forward values
            // Held to avoid consuming joinerSecret
            this.welcomeSecret = memberSecret.deriveSecret(suite, "welcome");
            this.welcomeKey = welcomeSecret.expand(suite, "key", suite.getAEAD().getKeySize());
            this.welcomeNonce = welcomeSecret.expand(suite, "nonce", suite.getAEAD().getNonceSize());
        }
        public void injectPskSecret(Secret pskSecret) throws IOException
        {
            this.memberSecret = Secret.extract(suite, joinerSecret, pskSecret);
            this.welcomeSecret = memberSecret.deriveSecret(suite, "welcome");
            this.welcomeKey = welcomeSecret.expand(suite, "key", suite.getAEAD().getKeySize());
            this.welcomeNonce = welcomeSecret.expand(suite, "nonce", suite.getAEAD().getNonceSize());
        }


        public KeyScheduleEpoch complete(TreeSize treeSize, byte[] context) throws IOException, IllegalAccessException {
            Secret epochSecret = memberSecret.expandWithLabel(suite, "epoch", context, suite.getKDF().getHashLength());
            return new KeyScheduleEpoch(suite, treeSize, epochSecret);
        }
    }

    public static class ExternalInitParams {
        final byte[] kemOutput;
        final Secret initSecret;

        public ExternalInitParams(CipherSuite suite, AsymmetricKeyParameter externalPub) {
            final byte[] exportContext = "MLS 1.0 external init secret".getBytes(StandardCharsets.UTF_8);
            final int L = suite.getKDF().getHashLength();

            HPKEContextWithEncapsulation ctx = suite.getHPKE().setupBaseS(externalPub, null);
            kemOutput = ctx.getEncapsulation();
            initSecret =  new Secret(ctx.export(exportContext, L));
        }

        public byte[] getKEMOutput() {
            return kemOutput;
        }
    }




    final CipherSuite suite;

    // Secrets derived from the epoch secret
    public final Secret initSecret;
    public Secret senderDataSecret;
    public final Secret exporterSecret;
    public final Secret confirmationKey;
    public Secret membershipKey;
    public final Secret resumptionPSK;
    public final Secret epochAuthenticator;
    public final Secret encryptionSecret;
    public final Secret externalSecret;

    // Further dervied products
    final AsymmetricCipherKeyPair externalKeyPair;
    final GroupKeySet groupKeySet;

    public GroupKeySet getEncryptionKeys(TreeSize size) throws IOException, IllegalAccessException
    {
        return new GroupKeySet(suite, size, encryptionSecret);
    }

    public static KeyGeneration senderDataKeys(CipherSuite suite, byte[] senderDataSecretBytes, byte[] ciphertext) throws IOException
    {
        Secret senderDataSecret = new Secret(senderDataSecretBytes);
        int sampleSize = suite.getKDF().getHashLength();
        byte[] sample = Arrays.copyOf(ciphertext, sampleSize);
        int keySize = suite.getAEAD().getKeySize();
        int nonceSize = suite.getAEAD().getNonceSize();
        Secret key = senderDataSecret.expandWithLabel(suite, "key", sample, keySize);
        Secret nonce = senderDataSecret.expandWithLabel(suite, "nonce", sample, nonceSize);
        return new KeyGeneration(0, key, nonce);
    }


    public static Secret welcomeSecret(CipherSuite suite, byte[] joinerSecret, List<PSKWithSecret> psk) throws IOException
    {
        Secret pskSecret = JoinSecrets.pskSecret(suite, psk);
        Secret extract = new Secret(suite.getKDF().extract(joinerSecret, pskSecret.value()));
        return extract.deriveSecret(suite, "welcome");
    }

    public static KeyScheduleEpoch forCreator(CipherSuite suite) throws IOException, IllegalAccessException {
        SecureRandom rng = new SecureRandom();
        return forCreator(suite, rng);
    }
    public static KeyScheduleEpoch forCreator(CipherSuite suite, SecureRandom rng)
            throws IOException, IllegalAccessException {
        byte[] epochSecret = new byte[suite.getKDF().getHashLength()];
        rng.nextBytes(epochSecret);
        TreeSize treeSize = TreeSize.forLeaves(1);
        return new KeyScheduleEpoch(suite, treeSize, new Secret(epochSecret));
    }
    public static KeyScheduleEpoch forCreator(CipherSuite suite, byte[] epochSecret)
            throws IOException, IllegalAccessException {
        TreeSize treeSize = TreeSize.forLeaves(1);
        return new KeyScheduleEpoch(suite, treeSize, new Secret(epochSecret));
    }

    public static KeyScheduleEpoch forExternalJoiner(CipherSuite suite, TreeSize treeSize, ExternalInitParams externalInitParams, Secret commitSecret, List<PSKWithSecret> psks, byte[] context) throws IOException, IllegalAccessException {
        return JoinSecrets.forMember(suite, externalInitParams.initSecret, commitSecret, psks, context).complete(treeSize, context);
    }

    public JoinSecrets startCommit(Secret commitSecret, List<PSKWithSecret> psks, byte[] context) throws IOException {
        return JoinSecrets.forMember(suite, initSecret, commitSecret, psks, context);
    }

    /*
                     epoch_secret
                          |
                          |
                          +--> DeriveSecret(., <label>)
                          |    = <secret>
                          |
                          V
                    DeriveSecret(., "init")
                          |
                          |
                          V
                    init_secret_[n]
     */

    public byte[] confirmationTag(byte[] confirmedTranscriptHash)
    {
        return suite.getKDF().extract(confirmationKey.value(), confirmedTranscriptHash);
    }
    public static KeyScheduleEpoch joiner(CipherSuite suite, byte[] joinerSecret, List<PSKWithSecret> psks, byte[] context) throws IOException, IllegalAccessException
    {
        TreeSize size = TreeSize.forLeaves(1);
        JoinSecrets joinSecrets = new JoinSecrets(suite, new Secret(joinerSecret), psks);
        return joinSecrets.complete(size, context);

    }
    public KeyScheduleEpoch(CipherSuite suite, TreeSize treeSize, Secret epochSecret) throws IOException, IllegalAccessException {
        this.suite = suite;
        this.initSecret = epochSecret.deriveSecret(suite, "init");
        this.senderDataSecret = epochSecret.deriveSecret(suite, "sender data");
        this.exporterSecret = epochSecret.deriveSecret(suite, "exporter");
        this.confirmationKey = epochSecret.deriveSecret(suite, "confirm");
        this.membershipKey = epochSecret.deriveSecret(suite, "membership");
        this.resumptionPSK = epochSecret.deriveSecret(suite, "resumption");
        this.epochAuthenticator = epochSecret.deriveSecret(suite, "authentication");

        this.externalSecret = epochSecret.deriveSecret(suite, "external");
        this.externalKeyPair = suite.getHPKE().deriveKeyPair(externalSecret.value());

        this.encryptionSecret = epochSecret.deriveSecret(suite, "encryption");
        this.groupKeySet = new GroupKeySet(suite, treeSize, encryptionSecret);
    }
//    public KeyScheduleEpoch copy()
//    {
//        return new KeyScheduleEpoch(suite, groupKeySet.secretTree.treeSize, )
//        this.suite = suite;
//        this.initSecret = epochSecret.deriveSecret(suite, "init");
//        this.senderDataSecret = epochSecret.deriveSecret(suite, "sender data");
//        this.exporterSecret = epochSecret.deriveSecret(suite, "exporter");
//        this.confirmationKey = epochSecret.deriveSecret(suite, "confirm");
//        this.membershipKey = epochSecret.deriveSecret(suite, "membership");
//        this.resumptionPSK = epochSecret.deriveSecret(suite, "resumption");
//        this.epochAuthenticator = epochSecret.deriveSecret(suite, "authentication");
//
//        this.externalSecret = epochSecret.deriveSecret(suite, "external");
//        this.externalKeyPair = suite.getHPKE().deriveKeyPair(externalSecret.value());
//
//        this.encryptionSecret = epochSecret.deriveSecret(suite, "encryption");
//        this.groupKeySet = new GroupKeySet(suite, treeSize, encryptionSecret);
//    }


    public KeyScheduleEpoch(CipherSuite suite, TreeSize treeSize, Secret joinerSecret, Secret pskSecret, byte[] context) throws IOException, IllegalAccessException {
        this.suite = suite;

        Secret memSecret = Secret.extract(suite, joinerSecret, pskSecret);
        Secret epochSecret = memSecret.expandWithLabel(suite, "epoch", context, suite.getKDF().getHashLength());

        this.senderDataSecret = epochSecret.deriveSecret(suite, "sender data");
        this.encryptionSecret = epochSecret.deriveSecret(suite, "encryption");
        this.exporterSecret = epochSecret.deriveSecret(suite, "exporter");
        this.epochAuthenticator = epochSecret.deriveSecret(suite, "authentication");
        this.externalSecret = epochSecret.deriveSecret(suite, "external");
        this.confirmationKey = epochSecret.deriveSecret(suite, "confirm");
        this.membershipKey = epochSecret.deriveSecret(suite, "membership");
        this.resumptionPSK = epochSecret.deriveSecret(suite, "resumption");
        this.initSecret = epochSecret.deriveSecret(suite, "init");

        this.externalKeyPair = suite.getHPKE().deriveKeyPair(externalSecret.value());

        this.groupKeySet = new GroupKeySet(suite, treeSize, encryptionSecret);
    }
    public KeyScheduleEpoch nextG(TreeSize treeSize, byte[] externalInit, Secret commitSecret, List<PSKWithSecret> psks, byte[] context) throws IOException, IllegalAccessException {
        Secret currentSecret = initSecret;
        if (externalInit != null)
        {
            currentSecret = new Secret(externalInit);
        }
        Secret preJoinerSecret = Secret.extract(suite, currentSecret, commitSecret);
        Secret joinerSecret = preJoinerSecret.expandWithLabel(suite, "joiner", context, suite.getKDF().getHashLength());

        return new KeyScheduleEpoch(this.suite, treeSize, joinerSecret, JoinSecrets.pskSecret(suite, psks), context);


    }

    public KeyScheduleEpoch next(TreeSize treeSize, byte[] externalInit, Secret commitSecret, List<PSKWithSecret> psks, byte[] context) throws IOException, IllegalAccessException {

        /*
            Secret currentSecret = initSecret;
            if (externalInit != null)
            {
                currentSecret = new Secret(externalInit);
            }
            Secret preJoinerSecret = Secret.extract(suite, currentSecret, commitSecret);
            Secret joinerSecret = preJoinerSecret.expandWithLabel(suite, "joiner", context, suite.getKDF().getHashLength());

            return new KeyScheduleEpoch(this.suite, treeSize, joinerSecret, JoinSecrets.pskSecret(suite, psks), context);


         */
        Secret currInitSecret = initSecret;
        if (externalInit != null) {
//            final byte[] exportContext = "MLS 1.0 external init secret".getBytes(StandardCharsets.UTF_8);
//            final int L = suite.getKDF().getHashLength();
//            HPKEContext ctx = suite.getHPKE().setupBaseR(externalInit, externalKeyPair, new byte[0]);
//            currInitSecret = new Secret(ctx.export(exportContext, L));
            currInitSecret = new Secret(externalInit);
        }

        JoinSecrets joinSecrets = JoinSecrets.forMember(suite, currInitSecret, commitSecret, psks, context);
        return joinSecrets.complete(treeSize, context);
    }

    public byte[] MLSExporter(String label, byte[] context, int length) throws IOException
    {
        return exporterSecret.deriveSecret(suite, label).expandWithLabel(suite,  "exported", suite.hash(context), length).value();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeyScheduleEpoch that = (KeyScheduleEpoch) o;
        // XXX(RLB): There no easy way to compare externalKeyPair values, so we skip that.
        // XXX(RLB): In general, we could probably be more parsimonious here.
        return suite.equals(that.suite) && initSecret.equals(that.initSecret) && senderDataSecret.equals(that.senderDataSecret) && exporterSecret.equals(that.exporterSecret) && confirmationKey.equals(that.confirmationKey) && membershipKey.equals(that.membershipKey) && resumptionPSK.equals(that.resumptionPSK) && epochAuthenticator.equals(that.epochAuthenticator) && groupKeySet.equals(that.groupKeySet);
    }

    public AsymmetricKeyParameter getExternalPublicKey() {
        return externalKeyPair.getPublic();
    }
}
