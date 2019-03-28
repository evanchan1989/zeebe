/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.gateway.impl.broker.cluster;

import io.atomix.cluster.ClusterMembershipEvent;
import io.atomix.cluster.ClusterMembershipEvent.Type;
import io.atomix.cluster.ClusterMembershipEventListener;
import io.atomix.cluster.Member;
import io.zeebe.protocol.impl.data.cluster.BrokerInfo;
import io.zeebe.transport.ClientOutput;
import io.zeebe.transport.SocketAddress;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.future.ActorFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public class BrokerTopologyManagerImpl extends Actor
    implements BrokerTopologyManager, ClusterMembershipEventListener {
  protected final ClientOutput output;
  protected final BiConsumer<Integer, SocketAddress> registerEndpoint;
  protected final AtomicReference<BrokerClusterStateImpl> topology;

  public BrokerTopologyManagerImpl(
      final ClientOutput output, final BiConsumer<Integer, SocketAddress> registerEndpoint) {
    this.output = output;
    this.registerEndpoint = registerEndpoint;
    this.topology = new AtomicReference<>(null);
  }

  public ActorFuture<Void> close() {
    return actor.close();
  }

  /** @return the current known cluster state or null if the topology was not fetched yet */
  @Override
  public BrokerClusterState getTopology() {
    return topology.get();
  }

  public void setTopology(BrokerClusterStateImpl topology) {
    this.topology.set(topology);
  }

  @Override
  public void event(ClusterMembershipEvent event) {
    final Member subject = event.subject();
    final Type eventType = event.type();
    final BrokerInfo brokerInfo = BrokerInfo.fromProperties(subject.properties());

    if (brokerInfo != null) {
      actor.call(
          () -> {
            final BrokerClusterStateImpl newTopology = new BrokerClusterStateImpl(topology.get());

            switch (eventType) {
              case MEMBER_ADDED:
                newTopology.addBrokerIfAbsent(brokerInfo.getNodeId());
                processProperties(brokerInfo, newTopology);
                break;

              case METADATA_CHANGED:
                processProperties(brokerInfo, newTopology);
                break;

              case MEMBER_REMOVED:
                newTopology.removeBroker(brokerInfo.getNodeId());
                processProperties(brokerInfo, newTopology);
                break;
            }

            topology.set(newTopology);
          });
    }
  }

  // Update topology information based on the distributed event
  private void processProperties(BrokerInfo remoteTopology, BrokerClusterStateImpl newTopology) {

    newTopology.setClusterSize(remoteTopology.getClusterSize());
    newTopology.setPartitionsCount(remoteTopology.getPartitionsCount());
    newTopology.setReplicationFactor(remoteTopology.getReplicationFactor());

    for (Integer partitionId : remoteTopology.getPartitionRoles().keySet()) {
      newTopology.addPartitionIfAbsent(partitionId);

      if (remoteTopology.getPartitionNodeRole(partitionId)) {
        newTopology.setPartitionLeader(partitionId, remoteTopology.getNodeId());
      }
    }

    final String clientAddress = remoteTopology.getApiAddress(BrokerInfo.CLIENT_API_PROPERTY);
    newTopology.setBrokerAddressIfPresent(remoteTopology.getNodeId(), clientAddress);
    registerEndpoint.accept(remoteTopology.getNodeId(), SocketAddress.from(clientAddress));
  }
}
