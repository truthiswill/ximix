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
package org.cryptoworkshop.ximix.client;

import java.util.Map;

import org.cryptoworkshop.ximix.client.connection.ServiceConnectionException;
import org.cryptoworkshop.ximix.common.util.Operation;

/**
 * Support interface for requesting a shuffle transcript.
 */
public interface ShuffleTranscriptsDownloadOperation
{
    /**
      * Return a map of the seeds and witness values to open the commitments to seed values made
     *  at the start of the shuffle.
     *
      * @param boardName the board the operationNumber should match with.
      * @param operationNumber the number of operation of interest.
      * @param nodes the node path of interest.
      * @throws org.cryptoworkshop.ximix.client.connection.ServiceConnectionException
      */
     Map<String, byte[][]> downloadShuffleSeedsAndWitnesses(
         String boardName,
         long operationNumber,
         String... nodes)
         throws ServiceConnectionException;

    /**
      * Download the transcripts for a shuffle. These come down as a series of streams representing
      * each step in the shuffle.
      *
      * @param boardName the board the operationNumber should match with.
      * @param operationNumber the number of operation of interest.
      * @param options the type of the transcript requested and any other settings required.
      * @param defaultListener the listener to notify as each transcript stream arrives.
      * @param nodes the node path of interest.
      * @throws org.cryptoworkshop.ximix.client.connection.ServiceConnectionException
      */
     Operation<ShuffleTranscriptsDownloadOperationListener> downloadShuffleTranscripts(
         String boardName,
         long operationNumber,
         ShuffleTranscriptOptions options,
         ShuffleTranscriptsDownloadOperationListener defaultListener,
         String... nodes)
         throws ServiceConnectionException;

}
