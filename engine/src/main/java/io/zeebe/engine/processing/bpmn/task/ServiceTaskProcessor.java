/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processing.bpmn.task;

import io.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.zeebe.engine.processing.bpmn.BpmnElementProcessor;
import io.zeebe.engine.processing.bpmn.behavior.BpmnBehaviors;
import io.zeebe.engine.processing.bpmn.behavior.BpmnEventSubscriptionBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnIncidentBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnStateBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnStateTransitionBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnVariableMappingBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.model.element.ExecutableServiceTask;
import io.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedEventWriter;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.instance.JobState.State;
import io.zeebe.msgpack.value.DocumentValue;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.util.Either;
import io.zeebe.util.collection.Tuple;

public final class ServiceTaskProcessor implements BpmnElementProcessor<ExecutableServiceTask> {

  private final JobRecord jobCommand = new JobRecord().setVariables(DocumentValue.EMPTY_DOCUMENT);

  private final ExpressionProcessor expressionBehavior;
  private final TypedCommandWriter commandWriter;
  private final BpmnIncidentBehavior incidentBehavior;
  private final BpmnStateBehavior stateBehavior;
  private final BpmnStateTransitionBehavior stateTransitionBehavior;
  private final BpmnVariableMappingBehavior variableMappingBehavior;
  private final BpmnEventSubscriptionBehavior eventSubscriptionBehavior;
  private final TypedEventWriter eventWriter;
  private final KeyGenerator keyGenerator;

  public ServiceTaskProcessor(final BpmnBehaviors behaviors) {
    eventSubscriptionBehavior = behaviors.eventSubscriptionBehavior();
    expressionBehavior = behaviors.expressionBehavior();
    commandWriter = behaviors.commandWriter();
    incidentBehavior = behaviors.incidentBehavior();
    stateBehavior = behaviors.stateBehavior();
    stateTransitionBehavior = behaviors.stateTransitionBehavior();
    variableMappingBehavior = behaviors.variableMappingBehavior();
    eventWriter = behaviors.eventWriter();
    keyGenerator = behaviors.keyGenerator();
  }

  @Override
  public Class<ExecutableServiceTask> getType() {
    return ExecutableServiceTask.class;
  }

  @Override
  public void onActivate(final ExecutableServiceTask element, final BpmnElementContext context) {
    /* todo:
     * x whatever was done previously when writing the element_activating event
     * x everything what was done during the processing of the element_activating (so onActivating)
     * x everything what was done during the processing of the element_activated (so onActivated)
     * - move state changes to event applier
     */

    final var elementInstanceKey = keyGenerator.nextKey();

    eventWriter.appendFollowUpEvent(
        elementInstanceKey, WorkflowInstanceIntent.ELEMENT_ACTIVATING, context.getRecordValue());

    stateBehavior.createElementInstanceInFlowScope(
        context, elementInstanceKey, context.getRecordValue()); // state change

    final var updatedContext =
        context.copy(
            elementInstanceKey,
            context.getRecordValue(),
            WorkflowInstanceIntent.ELEMENT_ACTIVATING);

    final var scopeKey = context.getElementInstanceKey();
    Either.<Failure, Void>right(null)
        .flatMap(
            ok ->
                variableMappingBehavior.applyInputMappings(updatedContext, element)) // state change
        .flatMap(
            ok ->
                eventSubscriptionBehavior.subscribeToEvents(
                    element, updatedContext)) // state change
        .flatMap(ok -> evaluateJobExpressions(element, scopeKey))
        .ifRightOrLeft(
            jobTypeAndRetries -> {
              stateTransitionBehavior.transitionToActivated(updatedContext); // state change
              createNewJob(updatedContext, element, jobTypeAndRetries); // state change
            },
            failure -> incidentBehavior.createIncident(failure, updatedContext)); // state change
  }

  @Override
  public void onActivating(final ExecutableServiceTask element, final BpmnElementContext context) {}

  @Override
  public void onActivated(final ExecutableServiceTask element, final BpmnElementContext context) {}

  @Override
  public void onCompleting(final ExecutableServiceTask element, final BpmnElementContext context) {

    variableMappingBehavior
        .applyOutputMappings(context, element)
        .ifRightOrLeft(
            ok -> {
              eventSubscriptionBehavior.unsubscribeFromEvents(context);
              stateTransitionBehavior.transitionToCompleted(context);
            },
            failure -> incidentBehavior.createIncident(failure, context));
  }

  @Override
  public void onCompleted(final ExecutableServiceTask element, final BpmnElementContext context) {

    stateTransitionBehavior.takeOutgoingSequenceFlows(element, context);

    stateBehavior.consumeToken(context);
    stateBehavior.removeElementInstance(context);
  }

  @Override
  public void onTerminating(final ExecutableServiceTask element, final BpmnElementContext context) {

    final var elementInstance = stateBehavior.getElementInstance(context);
    final long jobKey = elementInstance.getJobKey();
    if (jobKey > 0) {
      cancelJob(jobKey);
      incidentBehavior.resolveJobIncident(jobKey);
    }

    eventSubscriptionBehavior.unsubscribeFromEvents(context);

    stateTransitionBehavior.transitionToTerminated(context);
  }

  @Override
  public void onTerminated(final ExecutableServiceTask element, final BpmnElementContext context) {

    eventSubscriptionBehavior.publishTriggeredBoundaryEvent(context);

    incidentBehavior.resolveIncidents(context);

    stateTransitionBehavior.onElementTerminated(element, context);

    stateBehavior.consumeToken(context);
    stateBehavior.removeElementInstance(context);
  }

  @Override
  public void onEventOccurred(
      final ExecutableServiceTask element, final BpmnElementContext context) {

    eventSubscriptionBehavior.triggerBoundaryEvent(element, context);
  }

  private Either<Failure, Tuple<String, Long>> evaluateJobExpressions(
      final ExecutableServiceTask element, final long scopeKey) {
    return Either.<Failure, Void>right(null)
        .flatMap(ok -> expressionBehavior.evaluateStringExpression(element.getType(), scopeKey))
        .flatMap(
            jobType ->
                expressionBehavior
                    .evaluateLongExpression(element.getRetries(), scopeKey)
                    .map(retries -> new Tuple<>(jobType, retries)));
  }

  private void createNewJob(
      final BpmnElementContext context,
      final ExecutableServiceTask serviceTask,
      final Tuple<String, Long> jobTypeAndRetries) {

    final var type = jobTypeAndRetries.getLeft();
    final var retries = jobTypeAndRetries.getRight().intValue();

    jobCommand
        .setType(type)
        .setRetries(retries)
        .setCustomHeaders(serviceTask.getEncodedHeaders())
        .setBpmnProcessId(context.getBpmnProcessId())
        .setWorkflowDefinitionVersion(context.getWorkflowVersion())
        .setWorkflowKey(context.getWorkflowKey())
        .setWorkflowInstanceKey(context.getWorkflowInstanceKey())
        .setElementId(serviceTask.getId())
        .setElementInstanceKey(context.getElementInstanceKey());

    commandWriter.appendNewCommand(JobIntent.CREATE, jobCommand);
  }

  private void cancelJob(final long jobKey) {
    final State state = stateBehavior.getJobState().getState(jobKey);

    if (state == State.ACTIVATABLE || state == State.ACTIVATED || state == State.FAILED) {
      final JobRecord job = stateBehavior.getJobState().getJob(jobKey);
      commandWriter.appendFollowUpCommand(jobKey, JobIntent.CANCEL, job);
    }
  }
}
