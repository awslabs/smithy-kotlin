/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package aws.smithy.kotlin.runtime.tracing

// TODO - mark API's experimental

/**
 * A sink that receives events.
 */
public interface TraceProbe {
    /**
     * Called when an event has been posted to a [TraceSpan].
     * @param span The span in which the event occurred.
     * @param event The event which occurred.
     */
    // FIXME - can't take tracespan for metrics...
    public fun postEvent(span: TraceSpanData, event: TraceEvent)

    /**
     * Called when a [TraceSpan] has been created.
     * @param span The newly created span
     */
    public fun spanCreated(span: TraceSpan)

    /**
     * Called when a [TraceSpan] has been closed and no further events will be posted to it.
     * @param span The span which has been closed.
     */
    public fun spanClosed(span: TraceSpan)
}
