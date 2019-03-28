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
package io.zeebe.test.broker.protocol.clientapi;

import static io.zeebe.test.util.TestUtil.doRepeatedly;
import static io.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.atomix.cluster.AtomixCluster;
import io.atomix.cluster.discovery.BootstrapDiscoveryProvider;
import io.atomix.utils.net.Address;
import io.zeebe.exporter.api.record.Record;
import io.zeebe.exporter.api.record.value.DeploymentRecordValue;
import io.zeebe.exporter.api.record.value.JobRecordValue;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.clientapi.RecordType;
import io.zeebe.protocol.clientapi.ValueType;
import io.zeebe.protocol.impl.SubscriptionUtil;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.deployment.ResourceType;
import io.zeebe.protocol.intent.DeploymentIntent;
import io.zeebe.protocol.intent.IncidentIntent;
import io.zeebe.protocol.intent.JobBatchIntent;
import io.zeebe.protocol.intent.JobIntent;
import io.zeebe.protocol.intent.MessageIntent;
import io.zeebe.protocol.intent.WorkflowInstanceIntent;
import io.zeebe.test.broker.protocol.MsgPackHelper;
import io.zeebe.test.util.MsgPackUtil;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.transport.ClientTransport;
import io.zeebe.transport.SocketAddress;
import io.zeebe.transport.Transports;
import io.zeebe.transport.impl.util.SocketUtil;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.sched.ActorScheduler;
import io.zeebe.util.sched.clock.ControlledActorClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.junit.rules.ExternalResource;

public class ClientApiRule extends ExternalResource {

  public static final long DEFAULT_LOCK_DURATION = 10000L;
  private static final String DEFAULT_WORKER = "defaultWorker";
  private static final String DEFAULT_CLUSTER_NAME = "zeebe-cluster";

  protected final int nodeId;
  protected final Supplier<SocketAddress> atomixAddressSupplier;
  protected final int partitionCount;
  protected int nextPartitionId = 0;

  protected MsgPackHelper msgPackHelper;

  private final Int2ObjectHashMap<PartitionTestClient> testPartitionClients =
      new Int2ObjectHashMap<>();
  private final ControlledActorClock controlledActorClock = new ControlledActorClock();
  protected Supplier<SocketAddress> clientAddressSupplier;
  protected ClientTransport transport;
  protected int defaultPartitionId = -1;
  private AtomixCluster atomix;
  private ActorScheduler scheduler;
  private ObjectMapper objectMapper;

  public ClientApiRule(final Supplier<SocketAddress> atomixAddressSupplier) {
    this(0, 1, atomixAddressSupplier);
  }

  public ClientApiRule(final int nodeId, final int partitionCount, final Supplier<SocketAddress> atomixAddressSupplier) {
    this.nodeId = nodeId;
    this.partitionCount = partitionCount;
    this.atomixAddressSupplier = atomixAddressSupplier;
    objectMapper = new ObjectMapper();
  }

  @Override
  protected void before() throws Throwable {
    this.atomix =
        AtomixCluster.builder()
            .withMemberId("clientApiRule")
            .withClusterId(DEFAULT_CLUSTER_NAME)
            .withAddress(
                Address.from(
                    atomixAddressSupplier.get().host(), SocketUtil.getNextAddress().port()))
            .withMembershipProvider(
                BootstrapDiscoveryProvider.builder()
                    .withNodes(
                        Address.from(
                            atomixAddressSupplier.get().host(), atomixAddressSupplier.get().port()))
                    .build())
            .build();
    atomix.start().join();

    scheduler =
        ActorScheduler.newActorScheduler()
            .setCpuBoundActorThreadCount(1)
            .setActorClock(controlledActorClock)
            .build();
    scheduler.start();

    transport = Transports.newClientTransport("gateway").scheduler(scheduler).build();
    msgPackHelper = new MsgPackHelper();

    waitForTopology();
    atomix.getMembershipService().getMembers().stream()
        .forEach(
            m -> {
              final String addressProperty = m.properties().getProperty("clientAddress");
              if (addressProperty != null) {
                try {
                  final InetSocketAddress socketAddress =
                      objectMapper.readValue(addressProperty, InetSocketAddress.class);
                  clientAddressSupplier =
                      () ->
                          SocketAddress.from(
                              socketAddress.getHostString() + ":" + socketAddress.getPort());

                } catch (IOException e) {
                  e.printStackTrace();
                }
              }
            });
    transport.registerEndpoint(nodeId, clientAddressSupplier.get());

    final List<Integer> partitionIds = doRepeatedly(this::getPartitionIds).until(p -> !p.isEmpty());
    defaultPartitionId = partitionIds.get(0);
  }

  private void waitForTopology() {
    waitUntil(
        () ->
            atomix.getMembershipService().getMembers().stream()
                .anyMatch(member -> member.properties().getProperty("clientAddress") != null));
  }

  @Override
  protected void after() {
    atomix.stop().join();

    if (transport != null) {
      transport.close();
    }

    if (scheduler != null) {
      scheduler.stop();
    }
  }

  /** targets the default partition by default */
  public ExecuteCommandRequestBuilder createCmdRequest() {
    return new ExecuteCommandRequestBuilder(transport.getOutput(), nodeId, msgPackHelper)
        .partitionId(defaultPartitionId);
  }

  public ExecuteCommandRequestBuilder createCmdRequest(int partition) {
    return new ExecuteCommandRequestBuilder(transport.getOutput(), nodeId, msgPackHelper)
        .partitionId(partition);
  }

  public ControlMessageRequestBuilder createControlMessageRequest() {
    return new ControlMessageRequestBuilder(transport.getOutput(), nodeId, msgPackHelper);
  }

  public PartitionTestClient partitionClient() {
    return partitionClient(defaultPartitionId);
  }

  public PartitionTestClient partitionClient(final int partitionId) {
    if (!testPartitionClients.containsKey(partitionId)) {
      testPartitionClients.put(partitionId, new PartitionTestClient(this, partitionId));
    }
    return testPartitionClients.get(partitionId);
  }

  public ExecuteCommandRequest activateJobs(final String type) {
    return activateJobs(defaultPartitionId, type, DEFAULT_LOCK_DURATION);
  }

  public ExecuteCommandRequest activateJobs(
      final int partitionId, final String type, final long lockDuration, final int amount) {
    // to make sure that job already exist
    partitionClient(partitionId)
        .receiveJobs()
        .withIntent(JobIntent.CREATED)
        .withType(type)
        .getFirst();

    return createCmdRequest(partitionId)
        .type(ValueType.JOB_BATCH, JobBatchIntent.ACTIVATE)
        .command()
        .put("type", type)
        .put("worker", DEFAULT_WORKER)
        .put("timeout", lockDuration)
        .put("amount", amount)
        .put("jobs", Collections.emptyList())
        .done()
        .send();
  }

  public ExecuteCommandRequest activateJobs(
      final int partitionId, final String type, final long lockDuration) {
    return activateJobs(partitionId, type, lockDuration, 10);
  }

  public void waitForPartition(final int partitions) {
    waitUntil(() -> getPartitionIds().size() >= partitions);
  }

  @SuppressWarnings("unchecked")
  public List<Integer> getPartitionIds() {
    try {
      final AtomicInteger partitionsCount = new AtomicInteger(0);

      atomix.getMembershipService().getMembers().stream()
          .forEach(
              m -> {
                final String countProperty = m.properties().getProperty("partitionsCount");
                if (countProperty != null) {
                  final int count = Integer.parseInt(countProperty);
                  if (count > 0) {
                    partitionsCount.set(count);
                  }
                }
              });

      return IntStream.range(0, partitionsCount.get()).boxed().collect(Collectors.toList());
    } catch (final Exception e) {
      return Collections.EMPTY_LIST;
    }
  }

  public int getDefaultPartitionId() {
    return defaultPartitionId;
  }

  public ClientTransport getTransport() {
    return transport;
  }

  public ControlledActorClock getClock() {
    return controlledActorClock;
  }

  /** @return the workflow key */
  public Record<DeploymentRecordValue> deployWorkflow(BpmnModelInstance modelInstance) {
    final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    Bpmn.writeModelToStream(outStream, modelInstance);
    final byte[] resource = outStream.toByteArray();

    final Map<String, Object> deploymentResource = new HashMap<>();
    deploymentResource.put("resource", resource);
    deploymentResource.put("resourceType", ResourceType.BPMN_XML.name());
    deploymentResource.put("resourceName", "testProcess.bpmn");

    final ExecuteCommandResponse commandResponse =
        createCmdRequest()
            .partitionId(Protocol.DEPLOYMENT_PARTITION)
            .type(ValueType.DEPLOYMENT, DeploymentIntent.CREATE)
            .command()
            .put(DeploymentRecord.RESOURCES, Collections.singletonList(deploymentResource))
            .done()
            .sendAndAwait();

    assertThat(commandResponse.getIntent()).isEqualTo(DeploymentIntent.CREATED);

    return RecordingExporter.deploymentRecords(DeploymentIntent.DISTRIBUTED)
        .withRecordKey(commandResponse.getKey())
        .getFirst();
  }

  public void publishMessage(String messageName, String correlationKey) {
    publishMessage(messageName, correlationKey, MsgPackUtil.asMsgPack("{}"));
  }

  public void publishMessage(String messageName, String correlationKey, DirectBuffer variables) {
    publishMessage(messageName, correlationKey, variables, Duration.ofHours(1).toMillis());
  }

  public void publishMessage(
      String messageName, String correlationKey, DirectBuffer variables, long ttl) {
    final ExecuteCommandResponse response =
        createCmdRequest()
            .partitionId(partitionForCorrelationKey(correlationKey))
            .type(ValueType.MESSAGE, MessageIntent.PUBLISH)
            .command()
            .put("name", messageName)
            .put("correlationKey", correlationKey)
            .put("timeToLive", ttl)
            .put("variables", BufferUtil.bufferAsArray(variables))
            .done()
            .sendAndAwait();

    assertThat(response.getRecordType()).isEqualTo(RecordType.EVENT);
    assertThat(response.getIntent()).isEqualTo(MessageIntent.PUBLISHED);
  }

  public void cancelWorkflowInstance(long workflowInstanceKey) {
    final ExecuteCommandResponse response =
        createCmdRequest()
            .partitionId(Protocol.decodePartitionId(workflowInstanceKey))
            .type(ValueType.WORKFLOW_INSTANCE, WorkflowInstanceIntent.CANCEL)
            .key(workflowInstanceKey)
            .command()
            .done()
            .sendAndAwait();

    assertThat(response.getRecordType()).isEqualTo(RecordType.EVENT);
    assertThat(response.getIntent()).isEqualTo(WorkflowInstanceIntent.ELEMENT_TERMINATING);
  }

  public void completeJob(String jobType) {
    completeJob(jobType, MsgPackUtil.asMsgPack("{}"));
  }

  public void completeJob(String jobType, DirectBuffer variables) {
    activateJobs(jobType).await();

    final Record<JobRecordValue> jobRecord =
        RecordingExporter.jobRecords(JobIntent.ACTIVATED).withType(jobType).getFirst();

    final ExecuteCommandResponse response =
        createCmdRequest()
            .type(ValueType.JOB, JobIntent.COMPLETE)
            .key(jobRecord.getKey())
            .command()
            .put("variables", BufferUtil.bufferAsArray(variables))
            .done()
            .sendAndAwait();

    assertThat(response.getIntent()).isEqualTo(JobIntent.COMPLETED);
  }

  public ExecuteCommandResponse resolveIncident(long incidentKey) {
    return createCmdRequest()
        .partitionId(Protocol.decodePartitionId(incidentKey))
        .type(ValueType.INCIDENT, IncidentIntent.RESOLVE)
        .key(incidentKey)
        .command()
        .done()
        .sendAndAwait();
  }

  protected int nextPartitionId() {
    return nextPartitionId++ % partitionCount;
  }

  protected int partitionForCorrelationKey(String correlationKey) {
    return SubscriptionUtil.getSubscriptionPartitionId(
        BufferUtil.wrapString(correlationKey), partitionCount);
  }
}
