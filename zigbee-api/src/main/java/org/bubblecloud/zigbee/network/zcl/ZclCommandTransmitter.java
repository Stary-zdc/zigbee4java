/**
 * Copyright 2016 Tommi S.E. Laukkanen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bubblecloud.zigbee.network.zcl;

import org.bubblecloud.zigbee.api.cluster.impl.core.AbstractCommand;
import org.bubblecloud.zigbee.api.cluster.impl.core.ZCLFrame;
import org.bubblecloud.zigbee.network.ApplicationFrameworkMessageListener;
import org.bubblecloud.zigbee.network.ClusterMessage;
import org.bubblecloud.zigbee.network.impl.*;
import org.bubblecloud.zigbee.network.model.IEEEAddress;
import org.bubblecloud.zigbee.network.packet.af.AF_DATA_CONFIRM;
import org.bubblecloud.zigbee.network.packet.af.AF_DATA_REQUEST;
import org.bubblecloud.zigbee.network.packet.af.AF_INCOMING_MSG;
import org.bubblecloud.zigbee.network.port.ZigBeeNetworkManagerImpl;
import org.bubblecloud.zigbee.util.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ZCL command transmitter.
 *
 * @author Tommi S.E. Laukkanen
 */
public class ZclCommandTransmitter implements ApplicationFrameworkMessageListener {
    /**
     * The logger.
     */
    private static final Logger logger = LoggerFactory.getLogger(ZigBeeEndpointImpl.class);

    private ZigBeeNetworkManagerImpl networkManager;

    private List<ZclCommandListener> commandListeners = new ArrayList<ZclCommandListener>();

    public ZclCommandTransmitter(ZigBeeNetworkManagerImpl networkManager) {
        this.networkManager = networkManager;
    }

    public void addCommandListener(final ZclCommandListener listener) {
        final List<ZclCommandListener> modifiedCommandListeners = new ArrayList<ZclCommandListener>(commandListeners);
        modifiedCommandListeners.add(listener);
        commandListeners = Collections.unmodifiableList(modifiedCommandListeners);
    }

    public void removeCommandListener(final ZclCommandListener listener) {
        final List<ZclCommandListener> modifiedCommandListeners = new ArrayList<ZclCommandListener>(commandListeners);
        modifiedCommandListeners.remove(listener);
        commandListeners = Collections.unmodifiableList(modifiedCommandListeners);
    }

    @Override
    public boolean notify(final AF_INCOMING_MSG clusterMessage) {

        final ZCLFrame frame = new ZCLFrame(new ClusterMessageImpl(clusterMessage.getData(), clusterMessage.getClusterId()));
        final boolean isClientServerDirection = frame.getHeader().getFramecontrol().isClientServerDirection();
        final boolean isClusterSpecificCommand = frame.getHeader().getFramecontrol().isClusterSpecificCommand();
        final boolean isManufacturerExtension = frame.getHeader().getFramecontrol().isManufacturerExtension();
        final boolean isDefaultResponseEnabled = frame.getHeader().getFramecontrol().isDefaultResponseEnabled();

        final int sourceAddress = clusterMessage.getSrcAddr();
        final short sourceEnpoint = clusterMessage.getSrcEndpoint();
        final int destinationAddress = ApplicationFrameworkLayer.getAFLayer(networkManager).getZigBeeNetwork().getNode(IEEEAddress.toColonNotation(networkManager.getIeeeAddress())).getNetworkAddress();
        final short destinationEndpoint = clusterMessage.getDstEndpoint();

        final int profileId = ApplicationFrameworkLayer.getAFLayer(networkManager).getSenderEndpointProfileId(destinationEndpoint, clusterMessage.getClusterId());
        final int clusterId = clusterMessage.getClusterId();
        final byte commandId = frame.getHeader().getCommandId();
        final byte transactionId = frame.getHeader().getTransactionId();

        final byte[] commandPayload = frame.getPayload();

        if (isClusterSpecificCommand && !isManufacturerExtension) {
            logger.debug("Received cluster specific command: [ clusterId: " + clusterId + " commmandId: " + commandId + " ZCL Header: " + ByteUtils.toBase16(frame.getHeader().toByte())
                    + ", ZCL Payload: " + ByteUtils.toBase16(frame.getPayload())
                    + "]");

            final ZclCommandMessage commandMessage = new ZclCommandMessage();

            commandMessage.setSourceAddress(sourceAddress);
            commandMessage.setSourceEnpoint(sourceEnpoint);
            commandMessage.setDestinationAddress(destinationAddress);
            commandMessage.setDestinationEndpoint(destinationEndpoint);

            commandMessage.setTransactionId(transactionId);

            ZclCommand command = null;
            for (final ZclCommand candidate : ZclCommand.values()) {
                if (candidate.profileId == profileId && candidate.clusterId == clusterId && candidate.commandId == commandId) {
                    command = candidate;
                    break;
                }
            }

            if (command == null) {
                return false;
            }

            commandMessage.setCommand(command);
            ZclCommandProtocol.deserializePayload(commandPayload, commandMessage);

            logger.debug("<<< " + commandMessage.toString());

            for (final ZclCommandListener commandListener : commandListeners) {
                commandListener.commandReceived(commandMessage);
            }

            return true;
        }

        return false;
    }

    public void sendCommand(final ZclCommandMessage commandMessage) throws ZigBeeNetworkManagerException {
        synchronized (networkManager) {
            final ApplicationFrameworkLayer af = ApplicationFrameworkLayer.getAFLayer(networkManager);

            final int sourceAddress = ApplicationFrameworkLayer.getAFLayer(networkManager).getZigBeeNetwork().getNode(IEEEAddress.toColonNotation(networkManager.getIeeeAddress())).getNetworkAddress();
            commandMessage.setSourceAddress(sourceAddress);
            commandMessage.setSourceEnpoint(
                    af.getSendingEndpoint(commandMessage.getCommand().profileId,
                    commandMessage.getCommand().clusterId));

            final byte[] payload = ZclCommandProtocol.serializePayload(commandMessage.getCommand(), commandMessage.getFields());

            final AbstractCommand cmd = new AbstractCommand((byte) commandMessage.getCommand().commandId, null,
                    commandMessage.getCommand().isClientServerDirection, commandMessage.getCommand().isClusterSpecific);
            cmd.setPayload(payload);
            final ZCLFrame zclFrame = new ZCLFrame(cmd, true);
            if (commandMessage.getTransactionId() != null) {
                zclFrame.getHeader().setTransactionId(commandMessage.getTransactionId());
            }
            final ClusterMessage input = new org.bubblecloud.zigbee.api.cluster.impl.ClusterMessageImpl(
                    (short) commandMessage.getCommand().clusterId, zclFrame);

            final short sender = af.getSendingEndpoint(commandMessage.getCommand().profileId, commandMessage.getCommand().clusterId);
            final byte afTransactionId = af.getNextTransactionId(sender);
            final byte[] msg = input.getClusterMsg();

            logger.debug(">>> " + commandMessage.toString());

            AF_DATA_CONFIRM response = networkManager.sendAFDataRequest(new AF_DATA_REQUEST(
                    commandMessage.getDestinationAddress(), commandMessage.getDestinationEndpoint(), sender, input.getId(),
                    afTransactionId, (byte) (0) /*options*/, (byte) 0 /*radius*/, msg));

            if (response == null) {
                throw new ZigBeeNetworkManagerException("Unable to send cluster on the ZigBee network due to general error.");
            }

            if (response.getStatus()  != 0) {
                throw new ZigBeeNetworkManagerException("Unable to send cluster on the ZigBee network due to: "
                        + response.getStatus() + " (" + response.getErrorMsg() + ")");
            }

            return;
        }
    }

}
