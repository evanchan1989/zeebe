/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.clustering.base.partitions;

import io.atomix.core.election.LeaderElection;
import io.zeebe.servicecontainer.ServiceName;

public class PartitionServiceNames {

  public static ServiceName<Void> leaderOpenLogStreamServiceName(String raftName) {
    return ServiceName.newServiceName(
        String.format("raft.leader.%s.openLogStream", raftName), Void.class);
  }

  public static ServiceName<Partition> leaderPartitionServiceName(final String partitionName) {
    return ServiceName.newServiceName(
        String.format("cluster.base.partition.%s.leader", partitionName), Partition.class);
  }

  public static final ServiceName<LeaderElection> partitionLeaderElectionServiceName(
      String logName) {
    return ServiceName.newServiceName(
        String.format("cluster.base.partition.%s.leader.election", logName), LeaderElection.class);
  }

  public static final ServiceName<Void> partitionLeadershipEventListenerServiceName(
      String logName) {
    return ServiceName.newServiceName(
        String.format("cluster.base.partition.%s.role.listener", logName), Void.class);
  }

  public static ServiceName<Partition> followerPartitionServiceName(final String partitionName) {
    return ServiceName.newServiceName(
        String.format("cluster.base.partition.%s.follower", partitionName), Partition.class);
  }

  public static ServiceName<Void> partitionInstallServiceName(final String partitionName) {
    return ServiceName.newServiceName(
        String.format("cluster.base.partition.install.%s", partitionName), Void.class);
  }
}