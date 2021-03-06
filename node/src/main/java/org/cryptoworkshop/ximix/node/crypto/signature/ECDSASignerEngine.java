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
package org.cryptoworkshop.ximix.node.crypto.signature;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.math.ec.ECPoint;
import org.cryptoworkshop.ximix.client.connection.ServiceConnectionException;
import org.cryptoworkshop.ximix.client.connection.signing.ECDSASigningService.Type;
import org.cryptoworkshop.ximix.client.connection.signing.Participant;
import org.cryptoworkshop.ximix.client.connection.signing.SigID;
import org.cryptoworkshop.ximix.client.connection.signing.message.ECDSAFetchMessage;
import org.cryptoworkshop.ximix.client.connection.signing.message.ECDSAInitialiseMessage;
import org.cryptoworkshop.ximix.client.connection.signing.message.ECDSAPartialCreateMessage;
import org.cryptoworkshop.ximix.client.connection.signing.message.ECDSAPointMessage;
import org.cryptoworkshop.ximix.common.asn1.message.BigIntegerMessage;
import org.cryptoworkshop.ximix.common.asn1.message.ECPointMessage;
import org.cryptoworkshop.ximix.common.asn1.message.ErrorMessage;
import org.cryptoworkshop.ximix.common.asn1.message.IDMessage;
import org.cryptoworkshop.ximix.common.asn1.message.KeyIDMessage;
import org.cryptoworkshop.ximix.common.asn1.message.MessageReply;
import org.cryptoworkshop.ximix.common.asn1.message.ShareMessage;
import org.cryptoworkshop.ximix.common.asn1.message.SignatureMessage;
import org.cryptoworkshop.ximix.common.asn1.message.StoreMessage;
import org.cryptoworkshop.ximix.common.crypto.Algorithm;
import org.cryptoworkshop.ximix.common.crypto.threshold.ShamirSecretSplitter;
import org.cryptoworkshop.ximix.common.crypto.threshold.SplitSecret;
import org.cryptoworkshop.ximix.common.util.EventNotifier;
import org.cryptoworkshop.ximix.node.crypto.operator.ECPrivateKeyOperator;
import org.cryptoworkshop.ximix.node.crypto.util.BigIntegerShare;
import org.cryptoworkshop.ximix.node.crypto.util.ECPointShare;
import org.cryptoworkshop.ximix.node.crypto.util.Share;
import org.cryptoworkshop.ximix.node.crypto.util.ShareMap;
import org.cryptoworkshop.ximix.node.service.Decoupler;
import org.cryptoworkshop.ximix.node.service.NodeContext;
import org.cryptoworkshop.ximix.node.service.PrivateKeyOperator;

/**
 * Engine class providing threshold ECDSA signer based on "A Robust Threshold Elliptic Curve Digital Signature Providing a New Verifiable Secret Sharing Scheme" by Ibrahim, Ali, Ibrahim, and El-sawi.
 * See also "Theory and Practice of Verifiable Secret Sharing" by Rosario Gennaro, Chapter 4.
 */
public class ECDSASignerEngine
    extends SignerEngine
{
    private final ShareMap<SigID, BigInteger> sharedKMap;
    private final ShareMap<SigID, BigInteger> sharedAMap;
    private final ShareMap<SigID, BigInteger> sharedBMap;
    private final ShareMap<SigID, BigInteger> sharedCMap;
    private final ShareMap<SigID, ECPoint> sharedPMap;

    private final Map<String, ECDomainParameters> paramsMap = Collections.synchronizedMap(new HashMap<String, ECDomainParameters>());

    private final Map<SigID, BigInteger> muMap = Collections.synchronizedMap(new HashMap<SigID, BigInteger>());
    private final Map<SigID, BigInteger> rMap = Collections.synchronizedMap(new HashMap<SigID, BigInteger>());

    private final AtomicLong             idCounter = new AtomicLong(1);

    /**
     * Base constructor.
     *
     * @param nodeContext the context for the node we are associated with.
     */
    public ECDSASignerEngine(NodeContext nodeContext)
    {
        super(Algorithm.ECDSA, nodeContext);

        this.sharedKMap = new ShareMap<>(nodeContext.getScheduledExecutorService(), nodeContext.getDecoupler(Decoupler.SHARING), nodeContext.getEventNotifier());
        this.sharedPMap = new ShareMap<>(nodeContext.getScheduledExecutorService(), nodeContext.getDecoupler(Decoupler.SHARING), nodeContext.getEventNotifier());
        this.sharedAMap = new ShareMap<>(nodeContext.getScheduledExecutorService(), nodeContext.getDecoupler(Decoupler.SHARING), nodeContext.getEventNotifier());
        this.sharedBMap = new ShareMap<>(nodeContext.getScheduledExecutorService(), nodeContext.getDecoupler(Decoupler.SHARING), nodeContext.getEventNotifier());
        this.sharedCMap = new ShareMap<>(nodeContext.getScheduledExecutorService(), nodeContext.getDecoupler(Decoupler.SHARING), nodeContext.getEventNotifier());
    }

    public MessageReply handle(final SignatureMessage message)
    {
        try
        {
            switch ((Type)message.getType())
            {
            case FETCH_SEQUENCE_NO:
                KeyIDMessage keyIDMessage = KeyIDMessage.getInstance(message.getPayload());

                return new MessageReply(MessageReply.Type.OKAY, new BigIntegerMessage(BigInteger.valueOf(nodeContext.getPrivateKeyOperator(keyIDMessage.getKeyID()).getSequenceNo())));
            case FETCH_SIG_ID:
                SigID newID = new SigID(nodeContext.getName() + ".ECDSA." + idCounter.getAndIncrement());

                return new MessageReply(MessageReply.Type.OKAY, new IDMessage(newID.getID()));
            case FETCH_R:
                IDMessage idMessage = IDMessage.getInstance(message.getPayload());

                return new MessageReply(MessageReply.Type.OKAY, new BigIntegerMessage(rMap.get(new SigID(idMessage.getID()))));
            case INIT_K_AND_P:
                generateAndSendKAndP(message);

                return new MessageReply(MessageReply.Type.OKAY);
            case INIT_A:
                generateAndSendA(message);

                return new MessageReply(MessageReply.Type.OKAY);
            case INIT_B:
                generateAndSendZeroShare(message, Type.STORE_B, sharedBMap);

                return new MessageReply(MessageReply.Type.OKAY);
            case INIT_C:
                generateAndSendZeroShare(message, Type.STORE_C, sharedCMap);

                return new MessageReply(MessageReply.Type.OKAY);
            case INIT_R:
                initialiseR(message);

                return new MessageReply(MessageReply.Type.OKAY);
            case INIT_MU:
                initialiseMu(message);

                return new MessageReply(MessageReply.Type.OKAY);
            case STORE_K:
                addValue(sharedKMap, message);

                return new MessageReply(MessageReply.Type.OKAY);
            case STORE_A:
                addValue(sharedAMap, message);

                return new MessageReply(MessageReply.Type.OKAY);
            case STORE_B:
                addValue(sharedBMap, message);

                return new MessageReply(MessageReply.Type.OKAY);
            case STORE_C:
                addValue(sharedCMap, message);

                return new MessageReply(MessageReply.Type.OKAY);
            case STORE_P:
                StoreMessage            storeMessage = StoreMessage.getInstance(message.getPayload());
                ShareMessage            shareMessage = ShareMessage.getInstance(storeMessage.getSecretShareMessage());
                ECDSAPointMessage       pointMessage = ECDSAPointMessage.getInstance(shareMessage.getShareData());

                SigID sigID = new SigID(storeMessage.getID());
                ECDomainParameters domainParams = paramsMap.get(pointMessage.getKeyID());

                sharedPMap.addValue(sigID, new ECPointShare(shareMessage.getSequenceNo(), domainParams.getCurve().decodePoint(pointMessage.getPoint())));

                return new MessageReply(MessageReply.Type.OKAY);
            case FETCH_P:
                ECDSAFetchMessage fetchMessage = ECDSAFetchMessage.getInstance(message.getPayload());
                sigID = new SigID(fetchMessage.getSigID());

                Share<ECPoint> share = sharedPMap.getShare(sigID);

                return replyOkay(new ShareMessage(share.getSequenceNo(), new ECPointMessage(share.getValue())));
            case FETCH_MU:
                fetchMessage = ECDSAFetchMessage.getInstance(message.getPayload());
                sigID = new SigID(fetchMessage.getSigID());

                domainParams = paramsMap.get(fetchMessage.getKeyID());

                Share<BigInteger> kShare = sharedKMap.getShare(sigID);
                return replyOkay(new ShareMessage(kShare.getSequenceNo(), new BigIntegerMessage(kShare.getValue().multiply(
                    sharedAMap.getShare(sigID).getValue()).add(sharedBMap.getShare(sigID).getValue()).mod(domainParams.getN()))));
            case PRIVATE_KEY_SIGN:
                ECDSAPartialCreateMessage partialMessage = ECDSAPartialCreateMessage.getInstance(message.getPayload());

                sigID = new SigID(partialMessage.getSigID());

                PrivateKeyOperator operator = nodeContext.getPrivateKeyOperator(partialMessage.getKeyID());

                if (!(operator instanceof ECPrivateKeyOperator))
                {
                    return new MessageReply(MessageReply.Type.ERROR); // TODO
                }

                ECPrivateKeyOperator ecOperator = (ECPrivateKeyOperator)operator;

                Share<BigInteger> aShare = sharedAMap.getShare(sigID);
                BigInteger kInvShare = aShare.getValue().multiply(muMap.get(sigID).modInverse(ecOperator.getDomainParameters().getN()));
                BigInteger eComponent = partialMessage.getE();
                BigInteger dComponent = ecOperator.transform(rMap.get(sigID));

                MessageReply reply = replyOkay(new ShareMessage(ecOperator.getSequenceNo(), new BigIntegerMessage(kInvShare.multiply(eComponent.add(dComponent)).add(sharedCMap.getShare(sigID).getValue()))));
                // TODO: need to clean up state tables here.
                return reply;
            default:
                nodeContext.getEventNotifier().notify(EventNotifier.Level.ERROR, "Unknown command in ECDSASignerEngine.");

                return new MessageReply(MessageReply.Type.ERROR, new ErrorMessage("Unknown command in ECDSASignerEngine."));
            }
        }
        catch (Exception e)
        {
            nodeContext.getEventNotifier().notify(EventNotifier.Level.ERROR, "ECDSASignerEngine failure: " + e.getMessage(), e);

            return new MessageReply(MessageReply.Type.ERROR, new ErrorMessage("ECDSASignerEngine failure: " + e.getMessage()));
        }
    }

    private void addValue(ShareMap sharedValueTable, SignatureMessage message)
    {
        StoreMessage storeMessage = StoreMessage.getInstance(message.getPayload());
        SigID sigID = new SigID(storeMessage.getID());
        ShareMessage shareMessage = ShareMessage.getInstance(storeMessage.getSecretShareMessage());

        sharedValueTable.addValue(sigID, new BigIntegerShare(shareMessage.getSequenceNo(), BigIntegerMessage.getInstance(shareMessage.getShareData()).getValue()));
    }

    private void generateAndSendKAndP(SignatureMessage message)
        throws IOException
    {
        ECDSAInitialiseMessage ecdsaCreate = ECDSAInitialiseMessage.getInstance(message.getPayload());
        SubjectPublicKeyInfo pubKeyInfo = nodeContext.getPublicKey(ecdsaCreate.getKeyID());
        ECDomainParameters domainParams = ((ECPublicKeyParameters)PublicKeyFactory.createKey(pubKeyInfo)).getParameters();
        SigID sigID = new SigID(ecdsaCreate.getSigID());

        sharedKMap.init(sigID, ecdsaCreate.getNodesToUse().length);

        paramsMap.put(ecdsaCreate.getKeyID(), domainParams);

        BigInteger n = ecdsaCreate.getN();
        int nBitLength = n.bitLength();
        BigInteger k, r;

        do // generate r
        {
            do
            {
                k = new BigInteger(nBitLength, new SecureRandom());
            }
            while (k.equals(BigInteger.ZERO) || k.compareTo(n) >= 0);

            ECPoint p = domainParams.getG().multiply(k).normalize();

            // 5.3.3
            BigInteger x = p.getAffineXCoord().toBigInteger();

            r = x.mod(n);
        }
        while (r.equals(BigInteger.ZERO));

        ShamirSecretSplitter sss = new ShamirSecretSplitter(getNumberOfPeers(ecdsaCreate.getNodesToUse()), ecdsaCreate.getThreshold(), n, new SecureRandom());

        SplitSecret split = sss.split(k);

        execute(new SendShareTask(sigID, Type.STORE_K, ecdsaCreate.getNodesToUse(), sharedKMap, split.getShares()));

        sharedPMap.init(sigID, ecdsaCreate.getNodesToUse().length);

        execute(new SendPointShareTask(sigID, ecdsaCreate.getKeyID(), ecdsaCreate.getNodesToUse(), split.getShares()));
    }

    private int getNumberOfPeers(Participant[] nodesToUse)
    {
        int numberOfPeers = 0;

        for (int i = 0; i != nodesToUse.length; i++)
        {
            if (numberOfPeers < nodesToUse[i].getSequenceNo())
            {
                numberOfPeers = nodesToUse[i].getSequenceNo();
            }
        }

        return (numberOfPeers + 1) * 2; // number of peers is one more than highest sequence number
    }

    private void generateAndSendA(SignatureMessage message)
        throws IOException
    {
        ECDSAInitialiseMessage ecdsaCreate = ECDSAInitialiseMessage.getInstance(message.getPayload());
        BigInteger n = ecdsaCreate.getN();
        int nBitLength = n.bitLength();
        BigInteger a;

        SigID sigID = new SigID(ecdsaCreate.getSigID());
        Participant[] nodesToUse = ecdsaCreate.getNodesToUse();

        sharedAMap.init(sigID, nodesToUse.length);

        do
        {
            a = new BigInteger(nBitLength, new SecureRandom());
        }
        while (a.equals(BigInteger.ZERO) || a.compareTo(n) >= 0);

        ShamirSecretSplitter sss = new ShamirSecretSplitter(getNumberOfPeers(nodesToUse), ecdsaCreate.getThreshold(), n, new SecureRandom());

        SplitSecret split = sss.split(a);

        execute(new SendShareTask(sigID, Type.STORE_A, nodesToUse, sharedAMap, split.getShares()));
    }

    private void initialiseR(SignatureMessage message)
        throws IOException, ServiceConnectionException
    {
        ECDSAInitialiseMessage ecdsaCreate = ECDSAInitialiseMessage.getInstance(message.getPayload());
        SigID sigID = new SigID(ecdsaCreate.getSigID());
        ECDomainParameters domainParams = paramsMap.get(ecdsaCreate.getKeyID());

        sharedPMap.waitFor(sigID);        // wait till local P value set

        ECPoint p = accumulateECPoint(ecdsaCreate.getNodesToUse(), Type.FETCH_P, new ECDSAFetchMessage(ecdsaCreate.getSigID(), ecdsaCreate.getKeyID(), ecdsaCreate.getNodesToUse()), domainParams.getCurve(), domainParams.getN());
        // 5.3.3
        BigInteger x = p.getAffineXCoord().toBigInteger();
        BigInteger r = x.mod(domainParams.getN());

        rMap.put(sigID, r);
    }

    private void initialiseMu(SignatureMessage message)
        throws IOException, ServiceConnectionException
    {
        ECDSAInitialiseMessage ecdsaCreate = ECDSAInitialiseMessage.getInstance(message.getPayload());
        SigID sigID = new SigID(ecdsaCreate.getSigID());
        BigInteger n = ecdsaCreate.getN();

        muMap.put(sigID, accumulateBigInteger(ecdsaCreate.getNodesToUse(), Type.FETCH_MU, new ECDSAFetchMessage(ecdsaCreate.getSigID(), ecdsaCreate.getKeyID(), ecdsaCreate.getNodesToUse()), n));
    }

    private void generateAndSendZeroShare(SignatureMessage message, Type type, ShareMap shareMap)
        throws IOException
    {
        ECDSAInitialiseMessage ecdsaCreate = ECDSAInitialiseMessage.getInstance(message.getPayload());
        SigID sigID = new SigID(ecdsaCreate.getSigID());
        BigInteger n = ecdsaCreate.getN();

        shareMap.init(sigID, ecdsaCreate.getNodesToUse().length);

        ShamirSecretSplitter sss = new ShamirSecretSplitter(getNumberOfPeers(ecdsaCreate.getNodesToUse()), ecdsaCreate.getThreshold() * 2, n, new SecureRandom());

        SplitSecret split = sss.split(BigInteger.ZERO);

        execute(new SendShareTask(sigID, type, ecdsaCreate.getNodesToUse(), shareMap, split.getShares()));
    }

    private BigInteger calculateE(BigInteger n, byte[] message)
    {
        int log2n = n.bitLength();
        int messageBitLength = message.length * 8;

        if (log2n >= messageBitLength)
        {
            return new BigInteger(1, message);
        }
        else
        {
            BigInteger trunc = new BigInteger(1, message);

            trunc = trunc.shiftRight(messageBitLength - log2n);

            return trunc;
        }
    }

    private class SendShareTask
        implements Runnable
    {
        private final Type type;
        private final SigID sigID;
        private final Participant[] peers;
        private final ShareMap sharedValueMap;
        private final BigInteger[] messages;

        SendShareTask(SigID sigID, Type type, Participant[] peers, ShareMap sharedValueMap, BigInteger[] messages)
        {
            this.sigID = sigID;
            this.type = type;
            this.peers = peers;
            this.sharedValueMap = sharedValueMap;
            this.messages = messages;
        }

        public void run()
        {
            for (final Participant participant : peers)
            {
                if (participant.getName().equals(nodeContext.getName()))
                {
                    sharedValueMap.addValue(sigID, new BigIntegerShare(participant.getSequenceNo(), messages[participant.getSequenceNo()]));
                }
                else
                {
                    execute(new Runnable()
                    {
                        public void run()
                        {
                            try
                            {
                                MessageReply rep = sendMessage(participant.getName(), type, new StoreMessage(sigID.getID(), new ShareMessage(participant.getSequenceNo(), new BigIntegerMessage(messages[participant.getSequenceNo()]))));
                                if (rep.getType() != MessageReply.Type.OKAY)
                                {
                                    nodeContext.getEventNotifier().notify(EventNotifier.Level.ERROR, "SendShareTask failure " + rep.interpretPayloadAsError());
                                }
                            }
                            catch (ServiceConnectionException e)
                            {
                                nodeContext.getEventNotifier().notify(EventNotifier.Level.ERROR, "SendShareTask failure: " + e.toString());
                            }
                        }
                    });

                }
            }
        }
    }

    private class SendPointShareTask
        implements Runnable
    {
        private final SigID sigID;
        private final String keyID;
        private final Participant[] peers;
        private final BigInteger[] messages;

        SendPointShareTask(SigID sigID, String keyID, Participant[] peers, BigInteger[] messages)
        {
            this.sigID = sigID;
            this.keyID = keyID;
            this.peers = peers;
            this.messages = messages;
        }

        public void run()
        {
            ECDomainParameters domainParams = paramsMap.get(keyID);

            for (final Participant node : peers)
            {
                final ECPoint p = domainParams.getG().multiply(messages[node.getSequenceNo()]);

                if (node.getName().equals(nodeContext.getName()))
                {
                    sharedPMap.addValue(sigID, new ECPointShare(node.getSequenceNo(), p));
                }
                else
                {
                    schedule(new Runnable()
                    {
                        public void run()
                        {
                            try
                            {
                                MessageReply rep = sendMessage(node.getName(), Type.STORE_P, new StoreMessage(sigID.getID(), new ShareMessage(node.getSequenceNo(), new ECDSAPointMessage(keyID, p))));
                                if (rep.getType() != MessageReply.Type.OKAY)
                                {
                                    nodeContext.getEventNotifier().notify(EventNotifier.Level.ERROR, "SendPointShareTask failure " + rep.interpretPayloadAsError());
                                }
                            }
                            catch (ServiceConnectionException e)
                            {
                                nodeContext.getEventNotifier().notify(EventNotifier.Level.ERROR, "SendPointShareTask failure: " + e.toString());
                            }
                        }
                    }, 1000, TimeUnit.MILLISECONDS);
                }
            }
        }
    }
}
