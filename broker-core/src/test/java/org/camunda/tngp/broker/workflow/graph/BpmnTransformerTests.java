package org.camunda.tngp.broker.workflow.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.tngp.broker.workflow.graph.transformer.TngpExtensions.wrap;
import static org.camunda.tngp.test.util.BufferAssert.assertThatBuffer;

import java.util.List;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.tngp.broker.workflow.graph.model.ExecutableEndEvent;
import org.camunda.tngp.broker.workflow.graph.model.ExecutableFlowElement;
import org.camunda.tngp.broker.workflow.graph.model.ExecutableProcess;
import org.camunda.tngp.broker.workflow.graph.model.ExecutableSequenceFlow;
import org.camunda.tngp.broker.workflow.graph.model.ExecutableServiceTask;
import org.camunda.tngp.broker.workflow.graph.model.ExecutableStartEvent;
import org.camunda.tngp.broker.workflow.graph.transformer.BpmnTransformer;
import org.junit.Test;

public class BpmnTransformerTests
{
    private BpmnTransformer bpmnTransformer = new BpmnTransformer();

    @Test
    public void shouldTransformStartEvent()
    {
        // given
        final BpmnModelInstance bpmnModelInstance = Bpmn.createExecutableProcess()
            .startEvent("foo")
                .name("bar")
            .done();

        // when
        final ExecutableProcess process = transformSingleProcess(bpmnModelInstance);

        // then
        final ExecutableFlowElement element = process.findFlowElementById("foo");
        assertThat(element).isInstanceOf(ExecutableStartEvent.class);
        assertThat(element.getId()).isEqualTo("foo");
        assertThat(element.getName()).isEqualTo("bar");

        assertThat(process.getScopeStartEvent()).isEqualTo(element);
    }

    @Test
    public void shouldTransformSequenceFlow()
    {
        // given
        final BpmnModelInstance bpmnModelInstance = Bpmn.createExecutableProcess()
                .startEvent("a")
                .sequenceFlowId("to")
                .endEvent("b")
                .done();

        // when
        final ExecutableProcess process = transformSingleProcess(bpmnModelInstance);

        // then
        final ExecutableFlowElement element = process.findFlowElementById("to");
        assertThat(element).isInstanceOf(ExecutableSequenceFlow.class);

        final ExecutableSequenceFlow sequenceFlow = (ExecutableSequenceFlow) element;
        assertThat(sequenceFlow.getId()).isEqualTo("to");
        assertThat(sequenceFlow.getSourceNode().getId()).isEqualTo("a");
        assertThat(sequenceFlow.getTargetNode().getId()).isEqualTo("b");

        assertThat(sequenceFlow.getSourceNode().getOutgoingSequenceFlows()).hasSize(1).contains(sequenceFlow);
        assertThat(sequenceFlow.getTargetNode().getIncomingSequenceFlows()).hasSize(1).contains(sequenceFlow);
    }

    @Test
    public void shouldTransformEndEvent()
    {
        // given
        final BpmnModelInstance bpmnModelInstance = Bpmn.createExecutableProcess()
            .startEvent()
            .endEvent("foo")
                .name("bar")
            .done();

        // when
        final ExecutableProcess process = transformSingleProcess(bpmnModelInstance);

        // then
        final ExecutableFlowElement element = process.findFlowElementById("foo");
        assertThat(element).isInstanceOf(ExecutableEndEvent.class);
        assertThat(element.getId()).isEqualTo("foo");
        assertThat(element.getName()).isEqualTo("bar");
    }

    @Test
    public void shouldTransformServiceTask()
    {
        // given
        final BpmnModelInstance bpmnModelInstance = wrap(Bpmn.createExecutableProcess()
            .startEvent()
            .serviceTask("foo")
                .name("bar")
            .done())
                .taskDefinition("foo", "test", 4);

        // when
        final ExecutableProcess process = transformSingleProcess(bpmnModelInstance);

        // then
        final ExecutableFlowElement element = process.findFlowElementById("foo");
        assertThat(element).isInstanceOf(ExecutableServiceTask.class);

        final ExecutableServiceTask serviceTask = (ExecutableServiceTask) element;
        assertThat(serviceTask.getId()).isEqualTo("foo");
        assertThat(serviceTask.getName()).isEqualTo("bar");

        assertThat(serviceTask.getTaskMetadata()).isNotNull();
        assertThatBuffer(serviceTask.getTaskMetadata().getTaskType()).hasBytes("test".getBytes());
        assertThat(serviceTask.getTaskMetadata().getRetries()).isEqualTo(4);
    }

    protected ExecutableProcess transformSingleProcess(BpmnModelInstance bpmnModelInstance)
    {
        final List<ExecutableProcess> processes = bpmnTransformer.transform(bpmnModelInstance);

        assertThat(processes.size()).isEqualTo(1);

        return processes.get(0);
    }

}