package org.bouncycastle.jcajce.provider.asymmetric.ec;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Hashtable;

import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.BasicAgreement;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.DerivationFunction;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.agreement.ECDHCBasicAgreement;
import org.bouncycastle.crypto.agreement.ECMQVBasicAgreement;
import org.bouncycastle.crypto.agreement.kdf.ConcatenationKDFGenerator;
import org.bouncycastle.crypto.agreement.kdf.DHKDFParameters;
import org.bouncycastle.crypto.agreement.kdf.ECDHKEKGenerator;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.DESParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.KDFParameters;
import org.bouncycastle.crypto.params.MQVPrivateParameters;
import org.bouncycastle.crypto.params.MQVPublicParameters;
import org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import org.bouncycastle.jcajce.spec.MQVParameterSpec;
import org.bouncycastle.jcajce.spec.UserKeyingMaterialSpec;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.interfaces.MQVPrivateKey;
import org.bouncycastle.jce.interfaces.MQVPublicKey;
import org.bouncycastle.util.Integers;
import org.bouncycastle.util.Strings;

/**
 * Diffie-Hellman key agreement using elliptic curve keys, ala IEEE P1363
 * both the simple one, and the simple one with cofactors are supported.
 *
 * Also, MQV key agreement per SEC-1
 */
public class KeyAgreementSpi
    extends javax.crypto.KeyAgreementSpi
{
    private static final X9IntegerConverter converter = new X9IntegerConverter();
    private static final Hashtable algorithms = new Hashtable();
    private static final Hashtable algorithmNames = new Hashtable();
    private static final Hashtable oids = new Hashtable();
    private static final Hashtable des = new Hashtable();

    static
    {
        Integer i64 = Integers.valueOf(64);
        Integer i128 = Integers.valueOf(128);
        Integer i192 = Integers.valueOf(192);
        Integer i256 = Integers.valueOf(256);

        algorithms.put(NISTObjectIdentifiers.id_aes128_CBC.getId(), i128);
        algorithms.put(NISTObjectIdentifiers.id_aes192_CBC.getId(), i192);
        algorithms.put(NISTObjectIdentifiers.id_aes256_CBC.getId(), i256);
        algorithms.put(NISTObjectIdentifiers.id_aes128_wrap.getId(), i128);
        algorithms.put(NISTObjectIdentifiers.id_aes192_wrap.getId(), i192);
        algorithms.put(NISTObjectIdentifiers.id_aes256_wrap.getId(), i256);
        algorithms.put(PKCSObjectIdentifiers.id_alg_CMS3DESwrap.getId(), i192);
        algorithms.put(PKCSObjectIdentifiers.des_EDE3_CBC.getId(), i192);
        algorithms.put(OIWObjectIdentifiers.desCBC.getId(), i64);

        algorithmNames.put(NISTObjectIdentifiers.id_aes128_CBC.getId(), "AES");
        algorithmNames.put(NISTObjectIdentifiers.id_aes192_CBC.getId(), "AES");
        algorithmNames.put(NISTObjectIdentifiers.id_aes256_CBC.getId(), "AES");
        algorithmNames.put(NISTObjectIdentifiers.id_aes128_wrap.getId(), "AES");
        algorithmNames.put(NISTObjectIdentifiers.id_aes192_wrap.getId(), "AES");
        algorithmNames.put(NISTObjectIdentifiers.id_aes256_wrap.getId(), "AES");
        algorithmNames.put(PKCSObjectIdentifiers.id_alg_CMS3DESwrap.getId(), "DESEDE");
        algorithmNames.put(PKCSObjectIdentifiers.des_EDE3_CBC.getId(), "DESEDE");
        algorithmNames.put(OIWObjectIdentifiers.desCBC.getId(), "DES");

        oids.put("DESEDE", PKCSObjectIdentifiers.des_EDE3_CBC);
        oids.put("AES", NISTObjectIdentifiers.id_aes256_CBC);
        oids.put("DES", OIWObjectIdentifiers.desCBC);

        des.put("DES", "DES");
        des.put("DESEDE", "DES");
        des.put(OIWObjectIdentifiers.desCBC.getId(), "DES");
        des.put(PKCSObjectIdentifiers.des_EDE3_CBC.getId(), "DES");
        des.put(PKCSObjectIdentifiers.id_alg_CMS3DESwrap.getId(), "DES");
    }

    private String                 kaAlgorithm;
    private BigInteger             result;
    private ECDomainParameters     parameters;
    private BasicAgreement         agreement;
    private DerivationFunction     kdf;
    private MQVParameterSpec       mqvParameters;
    private UserKeyingMaterialSpec ukmParameters;

    private byte[] bigIntToBytes(
        BigInteger    r)
    {
        return converter.integerToBytes(r, converter.getByteLength(parameters.getCurve()));
    }

    protected KeyAgreementSpi(
        String kaAlgorithm,
        BasicAgreement agreement,
        DerivationFunction kdf)
    {
        this.kaAlgorithm = kaAlgorithm;
        this.agreement = agreement;
        this.kdf = kdf;
    }

    protected Key engineDoPhase(
        Key     key,
        boolean lastPhase) 
        throws InvalidKeyException, IllegalStateException
    {
        if (parameters == null)
        {
            throw new IllegalStateException(kaAlgorithm + " not initialised.");
        }

        if (!lastPhase)
        {
            throw new IllegalStateException(kaAlgorithm + " can only be between two parties.");
        }

        CipherParameters pubKey;        
        if (agreement instanceof ECMQVBasicAgreement)
        {
            if (!(key instanceof MQVPublicKey))
            {
                ECPublicKeyParameters staticKey = (ECPublicKeyParameters)
                    ECUtil.generatePublicKeyParameter((PublicKey)key);
                ECPublicKeyParameters ephemKey = (ECPublicKeyParameters)
                    ECUtil.generatePublicKeyParameter(mqvParameters.getOtherPartyEphemeralKey());

                pubKey = new MQVPublicParameters(staticKey, ephemKey);
            }
            else
            {
                MQVPublicKey mqvPubKey = (MQVPublicKey)key;
                ECPublicKeyParameters staticKey = (ECPublicKeyParameters)
                    ECUtil.generatePublicKeyParameter(mqvPubKey.getStaticKey());
                ECPublicKeyParameters ephemKey = (ECPublicKeyParameters)
                    ECUtil.generatePublicKeyParameter(mqvPubKey.getEphemeralKey());

                pubKey = new MQVPublicParameters(staticKey, ephemKey);

                // TODO Validate that all the keys are using the same parameters?
            }
        }
        else
        {
            if (!(key instanceof PublicKey))
            {
                throw new InvalidKeyException(kaAlgorithm + " key agreement requires "
                    + getSimpleName(ECPublicKey.class) + " for doPhase");
            }

            pubKey = ECUtil.generatePublicKeyParameter((PublicKey)key);

            // TODO Validate that all the keys are using the same parameters?
        }

        result = agreement.calculateAgreement(pubKey);

        return null;
    }

    protected byte[] engineGenerateSecret()
        throws IllegalStateException
    {
        if (kdf != null)
        {
            throw new UnsupportedOperationException(
                "KDF can only be used when algorithm is known");
        }

        return bigIntToBytes(result);
    }

    protected int engineGenerateSecret(
        byte[]  sharedSecret,
        int     offset) 
        throws IllegalStateException, ShortBufferException
    {
        byte[] secret = engineGenerateSecret();

        if (sharedSecret.length - offset < secret.length)
        {
            throw new ShortBufferException(kaAlgorithm + " key agreement: need " + secret.length + " bytes");
        }

        System.arraycopy(secret, 0, sharedSecret, offset, secret.length);
        
        return secret.length;
    }

    protected SecretKey engineGenerateSecret(
        String algorithm)
        throws NoSuchAlgorithmException
    {
        byte[] secret = bigIntToBytes(result);
        String algKey = Strings.toUpperCase(algorithm);
        String oidAlgorithm = algorithm;

        if (oids.containsKey(algKey))
        {
            oidAlgorithm = ((ASN1ObjectIdentifier)oids.get(algKey)).getId();
        }

        if (kdf != null)
        {
            if (!algorithms.containsKey(oidAlgorithm))
            {
                throw new NoSuchAlgorithmException("unknown algorithm encountered: " + algorithm);
            }
            
            int    keySize = ((Integer)algorithms.get(oidAlgorithm)).intValue();

            if (kdf instanceof ConcatenationKDFGenerator)
            {
                KDFParameters params = new KDFParameters(secret, ukmParameters.getUserKeyingMaterial());

                byte[] keyBytes = new byte[keySize / 8];
                kdf.init(params);
                kdf.generateBytes(keyBytes, 0, keyBytes.length);
                secret = keyBytes;
            }
            else
            {
                DHKDFParameters params = new DHKDFParameters(new ASN1ObjectIdentifier(oidAlgorithm), keySize, secret);

                byte[] keyBytes = new byte[keySize / 8];
                kdf.init(params);
                kdf.generateBytes(keyBytes, 0, keyBytes.length);
                secret = keyBytes;
            }
        }
        else
        {
            if (algorithms.containsKey(oidAlgorithm))
            {
                Integer length = (Integer)algorithms.get(oidAlgorithm);

                byte[] key = new byte[length.intValue() / 8];

                System.arraycopy(secret, 0, key, 0, key.length);

                secret = key;
            }
        }

        if (des.containsKey(oidAlgorithm))
        {
            DESParameters.setOddParity(secret);
        }

        return new SecretKeySpec(secret, getAlgorithmName(algorithm));
    }

    private String getAlgorithmName(String algorithm)
    {
        if (algorithmNames.containsKey(algorithm))
        {
            return (String)algorithmNames.get(algorithm);
        }

        return algorithm;
    }

    protected void engineInit(
        Key                     key,
        AlgorithmParameterSpec  params,
        SecureRandom            random) 
        throws InvalidKeyException, InvalidAlgorithmParameterException
    {
        if (params != null && !(params instanceof MQVParameterSpec || params instanceof UserKeyingMaterialSpec))
        {
            throw new InvalidAlgorithmParameterException("No algorithm parameters supported");
        }

        initFromKey(key, params);
    }

    protected void engineInit(
        Key             key,
        SecureRandom    random) 
        throws InvalidKeyException
    {
        initFromKey(key, null);
    }

    private void initFromKey(Key key, AlgorithmParameterSpec parameterSpec)
        throws InvalidKeyException
    {
        if (agreement instanceof ECMQVBasicAgreement)
        {
            mqvParameters = null;
            if (!(key instanceof MQVPrivateKey) && !(parameterSpec instanceof MQVParameterSpec))
            {
                throw new InvalidKeyException(kaAlgorithm + " key agreement requires "
                    + getSimpleName(MQVParameterSpec.class) + " for initialisation");
            }

            ECPrivateKeyParameters staticPrivKey;
            ECPrivateKeyParameters ephemPrivKey;
            ECPublicKeyParameters ephemPubKey;
            if (key instanceof MQVPrivateKey)
            {
                MQVPrivateKey mqvPrivKey = (MQVPrivateKey)key;
                staticPrivKey = (ECPrivateKeyParameters)
                    ECUtil.generatePrivateKeyParameter(mqvPrivKey.getStaticPrivateKey());
                ephemPrivKey = (ECPrivateKeyParameters)
                    ECUtil.generatePrivateKeyParameter(mqvPrivKey.getEphemeralPrivateKey());

                ephemPubKey = null;
                if (mqvPrivKey.getEphemeralPublicKey() != null)
                {
                    ephemPubKey = (ECPublicKeyParameters)
                        ECUtil.generatePublicKeyParameter(mqvPrivKey.getEphemeralPublicKey());
                }
            }
            else
            {
                MQVParameterSpec mqvParameterSpec = (MQVParameterSpec)parameterSpec;

                staticPrivKey = (ECPrivateKeyParameters)
                    ECUtil.generatePrivateKeyParameter((PrivateKey)key);
                ephemPrivKey = (ECPrivateKeyParameters)
                    ECUtil.generatePrivateKeyParameter(mqvParameterSpec.getEphemeralPrivateKey());

                ephemPubKey = null;
                if (mqvParameterSpec.getEphemeralPublicKey() != null)
                {
                    ephemPubKey = (ECPublicKeyParameters)
                        ECUtil.generatePublicKeyParameter(mqvParameterSpec.getEphemeralPublicKey());
                }
                mqvParameters = mqvParameterSpec;
            }

            MQVPrivateParameters localParams = new MQVPrivateParameters(staticPrivKey, ephemPrivKey, ephemPubKey);
            this.parameters = staticPrivKey.getParameters();

            // TODO Validate that all the keys are using the same parameters?

            agreement.init(localParams);
        }
        else
        {
            if (!(key instanceof PrivateKey))
            {
                throw new InvalidKeyException(kaAlgorithm + " key agreement requires "
                    + getSimpleName(ECPrivateKey.class) + " for initialisation");
            }

            ECPrivateKeyParameters privKey = (ECPrivateKeyParameters)ECUtil.generatePrivateKeyParameter((PrivateKey)key);
            this.parameters = privKey.getParameters();
            ukmParameters = (UserKeyingMaterialSpec)parameterSpec;
            agreement.init(privKey);
        }
    }

    private static String getSimpleName(Class clazz)
    {
        String fullName = clazz.getName();

        return fullName.substring(fullName.lastIndexOf('.') + 1);
    }

    public static class DH
        extends KeyAgreementSpi
    {
        public DH()
        {
            super("ECDH", new ECDHBasicAgreement(), null);
        }
    }

    public static class DHC
        extends KeyAgreementSpi
    {
        public DHC()
        {
            super("ECDHC", new ECDHCBasicAgreement(), null);
        }
    }

    public static class MQV
        extends KeyAgreementSpi
    {
        public MQV()
        {
            super("ECMQV", new ECMQVBasicAgreement(), null);
        }
    }

    public static class DHwithSHA1KDF
        extends KeyAgreementSpi
    {
        public DHwithSHA1KDF()
        {
            super("ECDHwithSHA1KDF", new ECDHBasicAgreement(), new ECDHKEKGenerator(new SHA1Digest()));
        }
    }

    public static class MQVwithSHA1KDF
        extends KeyAgreementSpi
    {
        public MQVwithSHA1KDF()
        {
            super("ECMQVwithSHA1KDF", new ECMQVBasicAgreement(), new ECDHKEKGenerator(new SHA1Digest()));
        }
    }

    public static class DHwithSHA1CKDF
        extends KeyAgreementSpi
    {
        public DHwithSHA1CKDF()
        {
            super("DHwithSHA1CKDF", new ECDHCBasicAgreement(), new ConcatenationKDFGenerator(new SHA1Digest()));
        }
    }

    public static class DHwithSHA256CKDF
        extends KeyAgreementSpi
    {
        public DHwithSHA256CKDF()
        {
            super("DHwithSHA256CKDF", new ECDHCBasicAgreement(), new ConcatenationKDFGenerator(new SHA256Digest()));
        }
    }
}
