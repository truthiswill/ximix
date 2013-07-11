/**
 * Copyright 2013 Crypto Workshop Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cryptoworkshop.ximix.crypto.key;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBMPString;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS12PfxPdu;
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder;
import org.bouncycastle.pkcs.PKCS12SafeBag;
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder;
import org.bouncycastle.pkcs.PKCS12SafeBagFactory;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.cryptoworkshop.ximix.common.asn1.XimixObjectIdentifiers;
import org.cryptoworkshop.ximix.common.service.KeyType;
import org.cryptoworkshop.ximix.common.service.NodeContext;
import org.cryptoworkshop.ximix.crypto.key.message.ECCommittedSecretShareMessage;
import org.cryptoworkshop.ximix.crypto.key.message.ECKeyGenParams;
import org.cryptoworkshop.ximix.crypto.threshold.ECCommittedSecretShare;
import org.cryptoworkshop.ximix.crypto.util.BigIntegerShare;
import org.cryptoworkshop.ximix.crypto.util.ECPointShare;
import org.cryptoworkshop.ximix.crypto.util.ShareMap;

public class ECKeyManager
    implements KeyManager
{
    private static final int TIME_OUT = 20;

    private final Map<String, ECDomainParameters> paramsMap = new HashMap<>();
    private final Map<String, BigInteger> hMap = new HashMap<>();
    private final Set<String> signingKeys = new HashSet<>();
    private final ShareMap<String, BigInteger> sharedPrivateKeyMap;
    private final ShareMap<String, ECPoint> sharedPublicKeyMap;
    private final NodeContext nodeContext;

    public ECKeyManager(NodeContext nodeContext)
    {
        this.nodeContext = nodeContext;

        sharedPublicKeyMap = new ShareMap<>(nodeContext.getScheduledExecutor());
        sharedPrivateKeyMap = new ShareMap<>(nodeContext.getScheduledExecutor());
    }

    @Override
    public synchronized boolean hasPrivateKey(String keyID)
    {
        return sharedPrivateKeyMap.containsKey(keyID);
    }

    @Override
    public synchronized boolean isSigningKey(String keyID)
    {
        return signingKeys.contains(keyID);
    }

    @Override
    public synchronized AsymmetricCipherKeyPair generateKeyPair(String keyID, KeyType algorithm, int numberOfPeers, ECKeyGenParams keyGenParams)
    {
        ECDomainParameters domainParameters = paramsMap.get(keyID);

        if (domainParameters == null)
        {
            X9ECParameters params = ECNamedCurveTable.getByName(keyGenParams.getDomainParameters());

            ECKeyPairGenerator kpGen = new ECKeyPairGenerator();

            kpGen.init(new ECKeyGenerationParameters(new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH(), params.getSeed()), new SecureRandom()));

            AsymmetricCipherKeyPair kp =  kpGen.generateKeyPair();

            sharedPrivateKeyMap.init(keyID, numberOfPeers);
            sharedPublicKeyMap.init(keyID, numberOfPeers);

            if (algorithm.equals(KeyType.ECDSA))
            {
                signingKeys.add(keyID);
            }

            hMap.put(keyID, keyGenParams.getH());
            paramsMap.put(keyID, ((ECPublicKeyParameters)kp.getPublic()).getParameters());

            return kp;
        }
        else
        {
            throw new IllegalStateException("Key " + keyID + " already exists.");
        }
    }

    @Override
    public SubjectPublicKeyInfo fetchPublicKey(String keyID)
        throws IOException
    {
        if (sharedPublicKeyMap.containsKey(keyID))
        {
            ECPoint q = sharedPublicKeyMap.getShare(keyID, TIME_OUT, TimeUnit.SECONDS).getValue();
            ECDomainParameters params = paramsMap.get(keyID);

            return SubjectPublicKeyInfo.getInstance(SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(new ECPublicKeyParameters(q, params)).getEncoded());
        }

        return null;
    }

    public synchronized void buildSharedKey(String keyID, ECCommittedSecretShareMessage message)
    {
        ECDomainParameters domainParams = paramsMap.get(keyID);
        ECCommittedSecretShare share = new ECCommittedSecretShare(message.getValue(), message.getWitness(), message.getCommitmentFactors());

        if (share.isRevealed(message.getIndex(), domainParams, hMap.get(keyID)))
        {
            sharedPrivateKeyMap.addValue(keyID, new BigIntegerShare(message.getIndex(), message.getValue()));
            sharedPublicKeyMap.addValue(keyID, new ECPointShare(message.getIndex(), message.getQ()));
        }
        else
        {
            throw new IllegalStateException("Commitment for " + keyID + " failed!");
        }
    }

    public BigInteger getPartialPrivateKey(String keyID)
    {
        return sharedPrivateKeyMap.getShare(keyID).getValue();
    }

    public byte[] getEncoded(char[] password)
        throws IOException, OperatorCreationException, GeneralSecurityException, PKCSException
    {
        KeyFactory fact = KeyFactory.getInstance("ECDSA", "BC");
        OutputEncryptor encOut = new JcePKCSPBEOutputEncryptorBuilder(NISTObjectIdentifiers.id_aes256_CBC).setProvider("BC").build(password);

        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        PKCS12PfxPduBuilder builder = new PKCS12PfxPduBuilder();

        for (String keyID : sharedPrivateKeyMap.getIDs())
        {
            ECDomainParameters domainParams = paramsMap.get(keyID);
            PrivateKey privKey = fact.generatePrivate(
                       new PKCS8EncodedKeySpec(
                            PrivateKeyInfoFactory.createPrivateKeyInfo(
                                new ECPrivateKeyParameters(sharedPrivateKeyMap.getShare(keyID).getValue(), domainParams)).getEncoded()));
            SubjectPublicKeyInfo pubKey = this.fetchPublicKey(keyID);

            PKCS12SafeBagBuilder eeCertBagBuilder = new PKCS12SafeBagBuilder(createCertificate(
                                                             keyID, sharedPrivateKeyMap.getShare(keyID).getSequenceNo(), privKey));

            eeCertBagBuilder.addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, new DERBMPString(keyID));

            SubjectKeyIdentifier pubKeyId = extUtils.createSubjectKeyIdentifier(pubKey);

            eeCertBagBuilder.addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, pubKeyId);

            PKCS12SafeBagBuilder keyBagBuilder = new JcaPKCS12SafeBagBuilder(privKey, encOut);

            keyBagBuilder.addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, new DERBMPString(keyID));
            keyBagBuilder.addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, pubKeyId);

            builder.addData(keyBagBuilder.build());

            builder.addEncryptedData(new JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.pbeWithSHAAnd128BitRC2_CBC).setProvider("BC").build(password), new PKCS12SafeBag[] { eeCertBagBuilder.build() });
        }

        PKCS12PfxPdu pfx = builder.build(new JcePKCS12MacCalculatorBuilder(NISTObjectIdentifiers.id_sha256), password);

        return pfx.getEncoded(ASN1Encoding.DL);
    }

    public void loadFromEncoding(char[] password, byte[] encoding)
        throws IOException, OperatorCreationException, GeneralSecurityException, PKCSException
    {
        PKCS12PfxPdu pfx = new PKCS12PfxPdu(encoding);
        InputDecryptorProvider inputDecryptorProvider = new JcePKCSPBEInputDecryptorProviderBuilder()
                                                              .setProvider("BC").build(password);
        ContentInfo[] infos = pfx.getContentInfos();

        for (int i = 0; i != infos.length; i++)
        {
            if (infos[i].getContentType().equals(PKCSObjectIdentifiers.encryptedData))
            {
                PKCS12SafeBagFactory dataFact = new PKCS12SafeBagFactory(infos[i], inputDecryptorProvider);

                PKCS12SafeBag[] bags = dataFact.getSafeBags();

                Attribute[] attributes = bags[0].getAttributes();

                X509CertificateHolder cert = (X509CertificateHolder)bags[0].getBagValue();

                String keyID = getKeyID(attributes);
                ECPublicKeyParameters publicKeyParameters = (ECPublicKeyParameters)PublicKeyFactory.createKey(cert.getSubjectPublicKeyInfo());

                paramsMap.put(keyID, publicKeyParameters.getParameters());
                sharedPublicKeyMap.init(keyID, 0);
                sharedPublicKeyMap.addValue(keyID, new ECPointShare(
                    ASN1Integer.getInstance(cert.getExtension(XimixObjectIdentifiers.ximixShareIdExtension).getParsedValue()).getValue().intValue(),
                    publicKeyParameters.getQ()));

                if (KeyUsage.fromExtensions(cert.getExtensions()).hasUsages(KeyUsage.digitalSignature))
                {
                    signingKeys.add(keyID);
                }
            }
            else
            {
                PKCS12SafeBagFactory dataFact = new PKCS12SafeBagFactory(infos[i]);

                PKCS12SafeBag[] bags = dataFact.getSafeBags();
                String keyID = getKeyID(bags[0].getAttributes());

                PKCS8EncryptedPrivateKeyInfo encInfo = (PKCS8EncryptedPrivateKeyInfo)bags[0].getBagValue();
                PrivateKeyInfo info = encInfo.decryptPrivateKeyInfo(inputDecryptorProvider);

                sharedPrivateKeyMap.init(keyID, 0);
                sharedPrivateKeyMap.addValue(keyID, new BigIntegerShare(0, ECPrivateKey.getInstance(info.parsePrivateKey()).getKey()));
            }
        }
    }

    // TODO: in this case we should get the private key from somewhere else - probably node config
    private X509CertificateHolder createCertificate(
        String keyID,
        int sequenceNo,
        PrivateKey privKey)
        throws GeneralSecurityException, OperatorCreationException, IOException
    {
        String name = "C=AU, O=Ximix Network Node, OU=" + nodeContext.getName();

        //
        // create the certificate - version 3
        //
        X509v3CertificateBuilder v3CertBuilder = new X509v3CertificateBuilder(
            new X500Name(name),
            BigInteger.valueOf(1),
            new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30),
            new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 365)),
            new X500Name(name),
            this.fetchPublicKey(keyID));

        // we use keyUsage extension to distinguish between signing and encryption keys
        // TODO;  distinguish between ElGamal and ECDSA

        if (signingKeys.contains(keyID))
        {
            v3CertBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        }
        else
        {
            v3CertBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.dataEncipherment));
        }

        v3CertBuilder.addExtension(XimixObjectIdentifiers.ximixShareIdExtension, true, new ASN1Integer(sequenceNo));

        return v3CertBuilder.build(new JcaContentSignerBuilder("SHA1withECDSA").setProvider("BC").build(privKey));
    }

    private String getKeyID(Attribute[] attributes)
    {
        for (Attribute attr : attributes)
        {
            if (PKCS12SafeBag.friendlyNameAttribute.equals(attr.getAttrType()))
            {
                return DERBMPString.getInstance(attr.getAttrValues().getObjectAt(0)).getString();
            }
        }

        throw new IllegalStateException("No friendlyNameAttribute found.");
    }
}